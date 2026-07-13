# Agent Context — vector-lab

Context for working on the `vector-lab` branch of json-iterator. Read this before
nontrivial changes; it encodes decisions and measured results that are expensive to
rediscover.

## The lab model (this branch's invariant)

**The `json-iterator` library sources on this branch stay byte-identical to `main`.**
All Vector API experimentation lives in the `jmh/` composite build:

- `jmh/src/vectorKernels/java` — vector kernels (`systems.comodal.jsoniter.jmh.vector`):
  currently `StructuralIndex` (simdjson-style stage 1), `Utf8Validator`, `JsonMinifier`,
  and `VectorSupport` (species selection). Kernels live in their own source set because
  the JMH bytecode generator reflects every class in the `jmh` source set in a JVM
  without the incubator module — classes with vector-typed fields must stay out of it.
- `jmh/src/jmh/java/.../jmh/vector` — kernel benches (`MinifyKernelBench`,
  `Stage1KernelBench`), sharing the kernels' package for package-private access.
  `DecodeBase64Bench` (library-API-driven, no kernel dependency) lives in the main
  bench package.

Consequences of the invariant:

- Rebasing this branch onto `main` never conflicts in library sources by construction.
  If a rebase does conflict there, someone broke the invariant — fix that first.
- There is no bug-fix porting: the library has one copy, on `main`.
- Kernel-vs-library comparisons use the library's public IOC hooks
  (`CharBufferFunction`, `applyChars*`, `FieldMatcher.match`) — see `DecodeBase64Bench`
  for the pattern.

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
  `./gradlew -p jmh jmh -PjmhIncludes=MinifyKernel -PjmhFork=3`. All `-Pjmh*` overrides
  come from the shared `software.sava.build.feature.jmh` convention (sibling
  `../sava-build` checkout), which also archives per-run results under
  `jmh/jmh-results/` and re-renders `build/results/jmh/results.txt` as the newest-wins
  merge of all archived runs.
- The published baseline resolves from GitHub Packages/Maven Central; the local library
  build is substituted via `includeBuild("..")`.
- Decision-grade comparisons need `-PjmhFork=3`+ and isolation from other load; this
  machine (Apple M-series) gets noisy under sustained benching. Kernel benches
  cross-check variants at `@Setup`; a mismatch is a correctness bug, not noise.
- `jmh/README.md` holds the migration decision table and measurement methodology
  inherited from main's FieldMatcher work.

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
- **`compress()` has no NEON lowering** — JsonMinifier uses run-based copies instead.
  `MinifyKernelBench` (3 forks, NEON): the vector minifier's win is workload-shaped,
  not universal — 1.7x over scalar on the Solana block (long base58 runs) but a tie on
  twitter (dense short strings, 525±38 vs 505±4 us). Minify promotion decisions should
  weigh the consumer's document shape.
- Fork-era standings vs published: published led short-string and skip-heavy workloads;
  the fork led long uniform runs (long strings ~1.5x, minify ~1.9x); parity on Solana
  full walks, doubles, dates. `IndexedJsonIterator` won selective access over large
  documents but its stage-1 fixed cost (~2.4 ms / 4.7 MiB) loses read-everything
  workloads.

## Closed doors (workload-structural — no hardware or JDK will reopen them)

- **Never vectorize the `char[]`-source paths.** Every surveyed consumer feeds `byte[]`
  (`parse(String)` routes through `getBytes()`); 16-bit lanes halve throughput forever;
  a perf-sensitive `char[]` holder should narrow to bytes once. Measured cost of the
  scalar revert on the retired fork: the vector `DECODE_BASE64` narrowing was a real
  3-6% end-to-end win (`DecodeBase64Bench`) — the door is closed by consumer reality,
  not because the vector code lost.
- **Never vectorize field-name scanning.** Surveyed names average 12.5 bytes, max ~34;
  a 32-byte SWAR prefix covers essentially all of them before a vector chunk engages.
- **Never vectorize dispatch comparison.** `FieldMatcher.match` is two word loads + a
  mix; chains win below ~8-10 names, the hash above — no regime fits a vector compare
  of ≤34-byte names or `matchString` values.

## Correctness landmines (each has bitten, on the retired fork)

- Silent-wrong-answer bugs cluster in per-byte/word/vector scanning paths (the
  published 21.0.12 multibyte corruption; base64 escape misdecoding; chars
  `handleEscapes` stripping without decoding). The library's position-sweep tests
  (`test_escape_positions_across_vector_widths`, `test_long_utf8_strings`,
  `testDecodeBase64Robustness`, `test_skip_until_tricky_field_names`) are the police
  for integration branches; kernel benches must cross-check outputs at `@Setup`.
- Multibyte lead bytes are `< 0` as signed bytes; bytes exactly `0x80` appear
  mid-character in common text. `\/` is legal JSON and `/` is in the base64 alphabet.
  Surrogate pairs arrive as two `\u` escapes and must pair-validate.
- javac processes `\u` escapes inside comments.
- JMH + incubator: vector-typed fields must not appear in generator-scanned classes
  (see the lab-model section); JMH forks inherit the parent JVM's flags.
