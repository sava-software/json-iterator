# Vector API Lab — Benchmark Conclusions

Results and verdicts from the kernel benchmarks on the `vector-lab` branch. The lab
model and methodology live in `../VECTOR-LAB.md`; run instructions and the shared
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
**`selectFrom` experiment — not adopted on NEON.** The same-run 2-fork ~5% gain
did not survive an isolated 3-fork confirmation: 2373 ± 77 (rearrange) vs
2344 ± 202 (selectFrom) — a statistical tie, textbook two-fork tease. The variant
stays benched for wide lanes, where table-lookup lowering differs.
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
**Integration attempt #1 (2026-07-12): FAILED, branch deleted.** The hybrid was
wired into `parse`/`skipPastEndQuote` on `integrate/string-scan-hybrid` (all 285
tests green, including the position-sweep police) and judged by the full suite
with a same-session main control: `blockParse_matcher` +11.5% slower (2779 ± 9 vs
2491 ± 13), masked +12%, chars +7%, kindDispatch +16%, `fieldWalkSolana` a tie —
kernel wins of 18-38% at these exact skip lengths inverted to losses once
integrated. Third independent confirmation that on NEON the integration barrier,
not kernel throughput, is the binding constraint. Leading suspect: method size —
the hybrid multiplies `parse`/`skipPastEndQuote` body size, plausibly breaking
their inlining into the skip/dispatch loops (unverified; would need
-XX:+PrintInlining on an integration branch).
**Remaining moves for Question 2:** (a) an inline-friendly integration shape —
keep the SWAR loop as the tiny method body and outline the vector tail behind a
cold-path call, so the common case inlines as before; (b) the wide-lane run,
where bigger kernel margins could survive integration overhead. Until one of
those measures a win, main's pure SWAR stands.

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

**Gating refuted (streak and one-shot variants, since removed).** Two hysteresis
designs were implemented and measured: probe gated on a 4-byte clean streak, and
the same gate with one-shot arming per run. Both achieved CJK parity (55-57 µs vs
scalar 55.9) but both paid an equal ~1.8x pathology on mixed-emoji content
(42 µs vs scalar 23.9) — and since one-shot firing did not help, the cost is the
gate itself: per-character streak/armed bookkeeping deforms the JIT's tight scalar
loop, not the probes it saves. Full final table (2 forks):

| Profile | scalar | vector | v1 always-probe | streak | one-shot |
|---|---|---|---|---|---|
| ascii_newlines | 38.6 | 9.7 | 11.8 | 15.7 | 15.1 |
| european | 42.6 | 64.5 | 38.4 | 36.4 | 38.5 |
| cjk | 55.9 | 375.3 | 88.7 | 55.1 | 56.9 |
| escaped_json | 32.0 | 96.9 | 36.4 | 37.8 | 40.9 |
| emoji_mixed | 23.9 | 21.9 | 24.3 | 42.6 | 42.2 |

**Per-string triage (`decodeTriage`) — GRADUATED, first shape to clear the
breadth bar.** One word-load at string entry: >=3 high-bit bytes or any escape
byte in the first word routes the whole string to pure scalar; everything else
takes the v1 adaptive path. Both hot loops stay unmodified (the
gate-in-the-loop lesson). Results (2 forks):

| Profile | scalar | triage | vs scalar |
|---|---|---|---|
| ascii_newlines | 38.6 | 11.4 | **3.4x faster** |
| european | 41.9 | 38.5 | 1.09x faster |
| escaped_json | 32.1 | 31.9 | parity |
| emoji_mixed | 23.4 | 23.9 | 1.02x (within noise) |
| cjk | 55.6 | 56.2 | 1.01x (within noise) |

Worst case ~2% — the first vector decode shape safe for arbitrary JSON content.
**Integration status: untested, but with better odds than Question 2's failure**
— `parseMultiByteString` is already a large, outlined cold-path method that the
JIT does not inline into hot loops, so the method-size mechanism suspected in the
Question 2 integration failure does not apply in the same way. Next action: an
integration branch off main replacing `parseMultiByteString`'s body with the
triage shape, judged by the full suite plus an escape/multibyte-heavy document
workload (the current suite's documents are ascii-dominated and would understate
both the win and any regression).

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
  the chars path — the door is closed by consumer reality (no surveyed consumer
  feeds `char[]`; 16-bit lanes halve throughput forever), not because the vector
  code lost.
  **Re-argued 2026-07-13 against main's new `SourceBench`; the door holds, and the
  prize is now sized.** The old supporting claim — "a `char[]` holder should narrow
  to bytes once" — turned out to be *wrong* and has been struck from both branches:
  for a UTF-16-backed String, `getBytes()` is a transcode (691 µs on twitter) while
  `toCharArray()` is a copy (65 µs), so a String-holding consumer should feed
  `char[]`, and main's docs now tell them to. The char path can therefore acquire
  users for the first time. It changes nothing, because the same measurements bound
  the upside: main puts the scalar char-path tax at **+10% (twitter) / +20%
  (solana)**, which for that consumer is 45 µs of a 560 µs parse — while routing
  them onto the right road already saved 579 µs. **A perfect vector char scan is
  worth ~5% of what the routing fix was worth**, and 3–6% is what it measured.
  Add the shipping cost — `jdk.incubator.vector` forces `--add-modules` on every
  downstream consumer — and the trade is not close. Reopening requires *both* a real
  hot-`char[]` consumer *and* a finalized Vector API.
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
