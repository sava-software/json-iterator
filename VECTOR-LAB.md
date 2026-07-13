# Vector Lab Charter — the `vector-lab` branch

Context for working on the `vector-lab` branch of json-iterator. Read this before
nontrivial changes; it encodes decisions and measured results that are expensive to
rediscover.

**Read `AGENTS.md` first — it is byte-identical to main's** and carries what both
branches share: the correctness landmines (the position-sweep tests that police every
scan-path change), the closed doors, the settled API and data-source verdicts, and the
benchmark discipline. This file adds only what is specific to the lab. Keeping the two
separate is deliberate: it is what lets this branch rebase onto `main` without ever
conflicting.

## The lab model (this branch's invariant)

**The `json-iterator` library sources on this branch stay byte-identical to `main`.**
All Vector API experimentation lives in the `jmh/` composite build:

- `jmh/src/vectorKernels/java` — vector kernels (`systems.comodal.jsoniter.jmh.vector`):
  currently `StructuralIndex` (simdjson-style stage 1), `Utf8Validator`, and
  `VectorSupport` (species selection). Kernels live in their own source set because
  the JMH bytecode generator reflects every class in the `jmh` source set in a JVM
  without the incubator module — classes with vector-typed fields must stay out of it.
- `jmh/src/jmh/java/.../jmh/vector` — kernel benches (`Stage1KernelBench`,
  `StringScanKernelBench`, `MultiByteDecodeKernelBench`, `SkipContainerKernelBench`,
  `Base64DecodeBench`), sharing the kernels' package for package-private access.

**Scope principle: consumer surveys gate API surface, never scan-path coverage.**
The parser must serve the breadth of JSON in the wild — international scripts,
escape density, every string-length regime — regardless of what the currently
surveyed consumers feed it. Performance questions on scan/decode paths are judged
across content shapes, not against one ecosystem's documents.

The lab holds four open questions (see jmh/VECTOR.md for data and action
triggers), each with a bench that tracks the
scalar-vs-vector delta directly:

1. **Stage-1 structural indexing on wide lanes** (`Stage1KernelBench`: vector vs
   scalar classification vs validating) — decides whether indexed navigation's
   economics revive once the Vector API finalizes. First NEON data: vector
   classification 2.34 ms vs scalar 14.3 ms on the 4.7 MiB block (6.1x) —
   empirical confirmation that scalar-backed indexing was rightly rejected;
   fused validation costs ~4%.
2. **String scanning: main's SWAR vs the fork's hybrid SWAR-prefix+vector**
   (`StringScanKernelBench`: widen and skip shapes across a string-length sweep).
   First NEON kernel data (2 forks): the skip shape crosses over between 24 and 44
   bytes — vector wins 18% at 44 B, 38% at 88 B (base58 key/signature lengths),
   3.1x at 512 B, 4x at 4 KiB; the widen shape crosses over near ~64 B (21% at
   88 B, 3x at 4 KiB), with vector no worse than ~7% below the crossover. This is
   the #1 integration candidate for a short-lived branch off main; the open part
   is whether integrated wins survive per-string dispatch and mixed-length
   documents (the fork's integrated skip-heavy losses say not automatically).
3. **Escape/multibyte decode: main's scalar byte-at-a-time vs the fork's
   vector run-copy** (`MultiByteDecodeKernelBench`, per the scope principle above:
   profiles sweep clean-run length between stops). First NEON data (2 forks) is
   sharply bimodal: vector wins 4x on sparse-stop content (log lines, escape
   every ~54 B) and ties at ~20-char runs (emoji_mixed), but loses 1.6x on
   European text, 3x on escape-dense embedded JSON, and 6.7x on dense CJK.
   Neither pure idiom is shippable for broad content: the fork's unconditional
   run-copy fast-paths English and punishes CJK. The open research question is
   an **adaptive decoder** — stay scalar until a clean-run streak justifies
   vector chunks (the SWAR-prefix insight applied per run), with the crossover
   near ~16-24 clean bytes — and whether wide lanes move that crossover.

Kernels whose feature verdict is already settled were removed
(JsonMinifier/MinifyKernelBench with the minify verdict; DecodeBase64Bench refereed
a closed door) — they remain in this branch's history and in `vectorize-archive`.

Consequences of the invariant:

- Rebasing this branch onto `main` never conflicts, in library sources or docs, by
  construction. If a rebase conflicts anywhere, someone broke the invariant — fix that
  first, don't resolve it away.
- **`AGENTS.md` stays byte-identical to main's; branch-specific context goes here.** That
  is the whole reason this file exists. It briefly was not: on 2026-07-13 main grew its
  own `AGENTS.md` while this branch's `AGENTS.md` held the charter, which would have
  conflicted on every single rebase — with the failure mode that someone resolves
  `theirs` and silently deletes the lab. If you find yourself wanting to add lab context
  to `AGENTS.md`, that is the mistake; add it here. If a genuinely *shared* truth is
  learned here (a closed door, a measurement lesson), it belongs in `AGENTS.md` on
  `main` and arrives on this branch by rebase — never by editing `AGENTS.md` here.
- There is no bug-fix porting: the library has one copy, on `main`.
- Kernel-vs-library comparisons use the library's public IOC hooks: implement each
  variant as a `CharBufferFunction` (or `FieldMatcher.match` call) and drive it through
  `applyChars*` on a real iterator — no library fork needed. The removed
  DecodeBase64Bench (in history) is the worked example.

**Integration experiments** (vectorizing the library's private scan loops) do not
happen here: cut a short-lived branch off `main`, wire the proven kernel in, run the
full suite and benches, then merge or delete. Kernel-level wins do not predict
integrated results — the SWAR-prefix/hit-cost interaction was only visible in
full-path benches.

**Promotion path for API features**: a feature that works with a scalar backend can be
promoted to `main` as an ordinary feature branch with the vector parts replaced by
scalar code; the vector kernels stay here until the Vector API finalizes (the
post-Valhalla bet) or a runtime gate with scalar fallbacks justifies shipping them.
Promotion candidates must clear the same bar as any main API: consumer-survey demand,
and no structural dominance by existing primitives. The 2026-07 promotion review of
the fork's features came up **empty** against that bar, which is the cautionary
precedent: scalar-backed `IndexedJsonIterator` fails structurally (scalar stage 1
classifies every byte slower than the plain iterator's SWAR skips, so single-pass
workloads — all surveyed consumers — cannot win; only parse-once-query-many could,
and nobody has that pattern); `at()` is a per-call-allocating alias for `skipUntil`
chains; minify serves an audience this library doesn't have. All three remain in
`vectorize-archive` with their real (vector) engines.

The retired long-lived research fork is preserved as the `vectorize-archive` tag
(the branch itself was deleted); its integrated implementations are the reference
for what each kernel looked like wired into the library —
`git checkout vectorize-archive`, or browse the tag on GitHub.

## Build, test, benchmark

- Benchmarks: `./gradlew -p jmh jmh` from the repo root, or filtered:
  `./gradlew -p jmh jmh -PjmhIncludes=Stage1Kernel -PjmhFork=3`. All `-Pjmh*` overrides
  come from the shared `software.sava.build.feature.jmh` convention (sibling
  `../sava-build` checkout), which also archives per-run results under
  `jmh/jmh-results/` and re-renders `build/results/jmh/results.txt` as the newest-wins
  merge of all archived runs.
- The published baseline resolves from GitHub Packages/Maven Central; the local library
  build is substituted via `includeBuild("..")`.
- Decision-grade comparisons need `-PjmhFork=3`+ and isolation from other load; this
  machine (Apple M-series) gets noisy under sustained benching. Kernel benches
  cross-check variants at `@Setup`; a mismatch is a correctness bug, not noise.
- `jmh/README.md` holds the migration decision table, the data-source decision table
  (`SourceBench`), and the measurement methodology inherited from main's FieldMatcher
  work. `jmh/VECTOR.md` is this branch's results document — benchmark conclusions,
  verdicts, and action triggers for the open questions; update it whenever a kernel
  bench produces decision-grade data.

The measurement rules live in `AGENTS.md` (fork counts, isolation, the 10%-error
contamination threshold, the same-session control, `uptime` before trusting a run,
`-prof gc` for costs that hide from the score). Two of them bite *harder* here than on
main, because this branch's entire method is scalar-vs-vector deltas:

- **Bench the scalar variant in the same run as the vector one — never against a number
  already sitting in `VECTOR.md`.** Those numbers are from other sessions on a machine
  that has been shown to move 23% under background load. Main learned this the expensive
  way: a "10-20% InputStream tax" reached its docs purely from a cross-run comparison,
  and a same-session control put the true figure at +1.4%. Every table in `VECTOR.md` is
  a historical record, not a baseline to measure against.
- **Judge any allocation-trading kernel on `gc.alloc.rate.norm`, not `avgt`.** Run-copy
  buffers and per-string scratch buy speed with garbage, and that cost can be *invisible*
  in the score — main's stream source ties on latency while allocating 22x more and
  spending 43x the GC time. The library targets a long-running service; the collector
  pays what the benchmark doesn't.

## Performance truths (measured on 128-bit NEON, Apple M-series, on the retired fork)

These will shift on AVX2/AVX-512/SVE — the standing task is re-measurement on wide
lanes (cloud x86 or Graviton/SVE; the jmh jar is self-contained).

- **`mask.toLong()` is the enemy on NEON.** Winning idioms: `anyTrue()` as the loop
  test, `firstTrue()` + one scalar byte inspection per hit, one OR-combined mask
  instead of per-marker masks.
- **Short strings dominate real JSON** and pay vector fixed costs per string; the
  integrated scans used a 32-byte SWAR prefix before vector chunks. Re-sweep that
  trade-off whenever hit-path economics change or lanes widen.
- **Dense-hit loops don't reward wide scanning** (structural container walking:
  scalar/SWAR won on both branches). The integrated vectorized `skipContainer` lost to
  the published scalar on skip-heavy workloads (fees/skipHeavy) on NEON — first
  candidate for the wide-lane verdict, and for deletion if wide lanes don't rescue it.
- **Scalar beats vector for fixed small work** (12-digit date validation, 8-digit SWAR
  vs scalar readLong ties-or-losses; M-series scalar IPC is very high).
- **Stage 1 is capped ~2 GB/s on NEON** by 12 `toLong()`s per 64-byte block
  (`Stage1KernelBench` reproduces this); the C++ simdjson `vpaddq` reduction tree is
  not expressible in the Vector API. First thing to re-measure on wide lanes.
- **`compress()` has no NEON lowering** — the (removed) JsonMinifier kernel used
  run-based copies instead. Its final measurements (3 forks, NEON) showed the vector
  win is workload-shaped, not universal: 1.7x over scalar on the Solana block (long
  base58 runs) but a tie on twitter (dense short strings, 525±38 vs 505±4 us).
- Fork-era standings vs published: published led short-string and skip-heavy workloads;
  the fork led long uniform runs (long strings ~1.5x, minify ~1.9x); parity on Solana
  full walks, doubles, dates. `IndexedJsonIterator` won selective access over large
  documents but its stage-1 fixed cost (~2.4 ms / 4.7 MiB) loses read-everything
  workloads.

## Closed doors

**The three closed doors live in `AGENTS.md`** — never vectorize the `char[]`-source
paths, field-name scanning, or dispatch comparison. They are shared with `main` because
they are workload-structural: no hardware and no JDK reopens them, so they bind the lab
as much as the library. Don't re-derive them here, and don't propose a kernel that walks
through one.

The `char[]` door was re-argued on 2026-07-13 against main's new `SourceBench` and it
held, but the argument changed shape and the full data is in `jmh/VECTOR.md`'s settled
verdicts. The short version, because it is the one most likely to be re-litigated *here*:
the old supporting claim "a `char[]` holder should narrow to bytes once" was **wrong** and
is struck — a String-holding consumer should feed `char[]`, so the char path can finally
acquire users. It reopens nothing, because the same measurements bound the prize: a
perfect vector char scan is worth **~5% of what merely routing that consumer correctly is
worth**, and 3-6% is what it measured. Add the shipping cost — `jdk.incubator.vector`
forces `--add-modules` on every downstream consumer — and it is not close. Reopening needs
*both* a real hot-`char[]` consumer *and* a finalized Vector API.

That shipping cost is the lab's own reason to exist: **nothing here can ship while the
Vector API incubates**, whatever it measures. Kernels are banked against a finalized API,
not queued for the next release.

## Correctness landmines specific to the lab

The library's landmines — the position-sweep tests that police every scan-path change,
signed lead bytes, `\/` versus the base64 alphabet, surrogate pairs, `\u` in comments —
are in `AGENTS.md` and apply to every integration branch cut from here. Two are peculiar
to this branch:

- **Kernel benches must cross-check their variants' outputs at `@Setup`.** A scalar/vector
  mismatch is a correctness bug, not noise, and `-foe true` turns it into a hard failure.
  Vector scan bugs are silent-wrong-answer bugs; a kernel that is fast and wrong looks
  exactly like a kernel that is fast.
- **JMH + incubator:** vector-typed fields must not appear in generator-scanned classes
  (see the lab-model section above); JMH forks inherit the parent JVM's flags.
