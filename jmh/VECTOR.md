# Vector API Lab — Benchmark Conclusions

Results and verdicts from the kernel benchmarks on the `vector-lab` branch. The lab
model and methodology live in `../AGENTS.md`; run instructions and the shared
measurement rules (3+ forks for decisions, isolation, error-bar hygiene) in
`README.md`. This file records what the numbers concluded.

**Provenance:** Apple M-series (128-bit NEON), JDK 27 EA (`jdk.incubator.vector`),
2-fork kernel runs (10 samples) under the service JVM flag set (ZGC, compact
headers, pinned pre-touched heap), 2026-07-12. Every number below is
hardware-conditional — NEON's expensive mask extraction is the recurring theme —
and the standing task is re-measurement on 256/512-bit lanes (cloud x86,
Graviton/SVE). Kernel wins do not automatically survive integration: the retired
fork's vectorized skip *lost* integrated skip-heavy workloads despite kernel-level
advantages, so every integration decision needs a full-path benchmark on a
short-lived branch off `main`.

## Question 1 — Stage-1 structural indexing (`Stage1KernelBench`)

Simdjson-style structural indexing of the 4.7 MiB Solana block:

| Variant | Time | Throughput |
|---|---|---|
| vector classification | 2.34 ms ± 0.10 | ~2.0 GB/s |
| vector + fused UTF-8 validation | 2.44 ms ± 0.02 | +4% |
| scalar classification | 14.33 ms ± 0.20 | **6.1× slower** |

**Conclusions.** The 6.1× vector advantage is empirical confirmation that
scalar-backed `IndexedJsonIterator` was rightly rejected for promotion — a scalar
stage 1 pays ~14 ms before reading a single value from this document. The vector
version remains capped ~2 GB/s on NEON by the 12 `mask.toLong()` extractions per
64-byte block (the C++ simdjson `vpaddq` reduction tree is not expressible in the
Vector API). Fused validation is nearly free (~4%).
**`selectFrom` experiment:** replacing the nibble lookup's `toShuffle`+`rearrange`
with `selectFrom` (NEON TBL-shaped) measured 2.24 ms vs 2.35 ms same-run — a ~5%
gain with barely separated error bars. Promising; adopt as the default
classification after an isolated 3-fork confirmation.
**Action trigger:** wide-lane re-measurement. If ≥256-bit lanes lift stage 1 well
past NEON's cap, indexed navigation's economics revive alongside the finalized
Vector API; if not, the indexed bet dies on all hardware.

## Question 2 — String scanning: SWAR vs hybrid SWAR-prefix+vector (`StringScanKernelBench`)

Main's word-at-a-time scan loops (the incumbent) versus the fork's hybrid (32-byte
SWAR prefix, then `anyTrue`/`firstTrue` vector chunks), over clean-ascii strings.
Two shapes: *skip* (`skipPastEndQuote` — find the closing quote) and *widen*
(`parse` — find it and copy chars into `charBuf`).

| Content length | skip: SWAR | skip: hybrid | widen: SWAR | widen: hybrid |
|---|---|---|---|---|
| 8 B | 1.02 µs | 0.94 (+8%) | 2.15 | 2.31 (−7%) |
| 24 B | 1.69 | 1.74 (−3%) | 3.72 | 3.93 (−6%) |
| 44 B (base58 key) | 2.25 | **1.84 (+18%)** | 5.50 | 5.81 (−6%) |
| 88 B (base58 sig) | 4.08 | **2.52 (+38%)** | 9.02 | **7.17 (+21%)** |
| 512 B | 19.07 | **6.14 (3.1×)** | 43.2 | **16.5 (2.6×)** |
| 4 KiB | 150.1 | **38.0 (4.0×)** | 326.7 | **108.3 (3.0×)** |

**Conclusions.** The skip shape crosses over between 24 and 44 bytes and is already
winning at exactly the string lengths that dominate Solana documents (base58 keys
and signatures) — today, on the weakest vector hardware. The widen shape crosses
near ~64 bytes; below the crossovers the hybrid costs at most ~7%. This is the
lab's **#1 integration candidate**.
**Action trigger:** a short-lived integration branch off `main` wiring the hybrid
into `parse`/`skipPastEndQuote`, judged by the full suite (`fees`, `fieldWalk`,
`blockParse`, twitter walks) — the open question is precisely whether these kernel
wins survive per-string dispatch and mixed-length documents, which the fork's
integrated skip-heavy losses show is not automatic. Re-sweep `SWAR_PREFIX` (32)
during that experiment; the 16-vs-32 trade-off interacts with hit cost.

## Question 3 — Escape/multibyte decode: scalar vs vector run-copy (`MultiByteDecodeKernelBench`)

Main's byte-at-a-time `parseMultiByteString` versus the fork's variant that
bulk-copies clean-ascii runs between stops (escapes, multibyte lead bytes) with
vector chunks. Profiles sweep the deciding axis — clean-run length between stops —
across the breadth of real-world JSON content (scope principle: consumer surveys
gate API surface, never scan-path coverage):

| Profile | Clean-run length | scalar | vector | Verdict |
|---|---|---|---|---|
| ascii_newlines (log lines) | ~54 B | 40.0 µs | 10.0 | **vector 4.0×** |
| emoji_mixed | ~20 chars | 23.2 | 22.1 | tie |
| european (accented latin) | ~12 chars | 41.0 | 66.9 | scalar 1.6× |
| escaped_json (embedded JSON) | ~5 chars | 32.4 | 95.7 | scalar 3.0× |
| cjk (dense 3-byte) | ~0 | 55.3 | 372.2 | **scalar 6.7×** |

**Conclusions.** Sharply bimodal: neither pure idiom is shippable for broad
content. The fork's unconditional run-copy fast-paths English-like content and
punishes CJK by nearly 7× — the canonical example of why single-consumer-shaped
benchmarking must not drive scan-path decisions. The crossover sits near clean
runs of ~16–24 bytes.
**Adaptive decoder (first iteration, `decodeAdaptive`):** scalar with a per-run
SWAR probe (up to 3 clean words) before entering vector chunks. Results:

| Profile | scalar | vector | adaptive |
|---|---|---|---|
| ascii_newlines | 38.6 | 9.9 | **11.1** — keeps ~90% of the vector win |
| european | 41.1 | 64.4 | **37.5** — beats both pure idioms |
| emoji_mixed | 23.3 | 22.2 | 23.3 — tie |
| escaped_json | 34.0 | 98.3 | 35.4 — vector's 3x loss neutralized |
| cjk | 56.9 | 385.3 | 89.9 — 6.7x loss collapsed to 1.6x, still losing |

The adaptive shape is directionally right: it captures the sparse-run upside and
neutralizes most of the downside. Remaining flaw: the SWAR probe itself costs one
word-load per run, which dense-stop content pays on every character (the CJK 1.6x).
**Next refinement:** hysteresis — only probe after N consecutive clean scalar bytes
(a multibyte character strongly predicts another), targeting CJK parity while
keeping the log-line win. Not integration-ready until CJK reaches ~1.0x.

## Question 4 — Container skipping (`SkipContainerKernelBench`)

The standing question from the fork era (its integrated vector walk lost skip-heavy
workloads). Kernel-level, across structural-density profiles — scalar level-count
walk vs the fork's OR-combined-mask vector walk:

| Profile | scalar | vector | Verdict |
|---|---|---|---|
| numericDense (balance arrays) | 16.4 µs | 3.75 | **vector 4.4×** |
| stringHeavy (solana meta shape) | 5.24 | 8.52 | scalar 1.6× |
| nested (dense braces) | 18.5 | 35.7 | scalar 1.9× |

**Conclusions.** The density axis was mis-framed in the fork era: what matters is
the density of *this walk's* stop set (brackets + quotes), not structural characters
generally. Long numeric arrays (`preBalances` — commas are not stops) are a genuine
vector sweet spot at 4.4×; quote-dense and brace-dense shapes lose, explaining the
fork's integrated losses on mixed real documents. A future integrated skip could
plausibly dispatch by container's first bytes — but that is speculative tuning;
the recorded verdict is: vector container skipping pays only on bracket-sparse,
quote-free spans.
**Action trigger:** wide-lane re-measurement (cheap mask extraction changes the
loss cases), and any adaptive-skip idea must beat the scalar walk on stringHeavy
and nested before integration is considered.

## Settled verdicts (benches since removed; data preserved here and in history)

- **Minify** (`MinifyKernelBench`, removed with the minify feature verdict): the
  vector minifier's win was workload-shaped, not universal — 1.7× over scalar on
  the Solana block (long base58 runs), a tie on twitter (dense short strings,
  525 ± 38 vs 505 ± 4 µs). Moot for this library: minify serves an audience it
  doesn't have.
- **Char-source base64 narrowing** (`DecodeBase64Bench`, removed with the closed
  door): the vector `char[]→byte[]` narrowing was a real 3–6% end-to-end win on
  the chars path — the door is closed by consumer reality (all consumers feed
  `byte[]`; 16-bit lanes halve throughput forever), not because the vector code
  lost.
- **Scalar-backed indexed navigation**: rejected structurally during the 2026-07
  promotion review; Question 1's 6.1× scalar penalty is the measurement behind it.
- **Custom vector base64 decode** (`Base64DecodeBench`): no headroom. The JDK
  decoder's HotSpot intrinsic is active and fast on aarch64 — 6.2–7.8 GB/s,
  6.5× ahead of a plain scalar table decode. A Mula-style Vector API kernel has
  nothing to offer; the base64 opportunity is confined to the quote-scan
  (Question 2's skip shape).

## Synthesis

On 128-bit NEON today, the Vector API earns its keep in exactly two regimes:
**long uniform runs** (string scan/skip beyond ~40–64 B, stage-1 block
classification) and **wherever mask extraction can be avoided**
(`anyTrue`/`firstTrue` loops). It loses or ties wherever stops are dense (CJK
decode, escape-heavy content, structural walking) or the work item is short
(field names, small enums, sub-24 B strings). Every idiom that wins here should
widen on AVX2/AVX-512/SVE — which is why the three open questions all share the
same next step: the wide-lane run.
