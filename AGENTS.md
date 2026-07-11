# Agent Context

Context for working on json-iterator. Read this before nontrivial changes; it encodes
decisions and measured results that are expensive to rediscover.

## What this library is

A pull-style JSON iterator (`systems.comodal.jsoniter`) whose defining feature is the
**inversion-of-control API**: real consumers (see
`../sava/sava-rpc/.../response/Block.java`) never materialize field names or
string values as `String`s — they dispatch on char buffers via
`testObject`/`fieldEquals`/`applyChars*`. Optimizations must target these IOC paths, not
just `readString`. Benchmarks that consume via `String` allocation misrepresent real usage.

## Branch semantics

- **`main`** — the published scalar line (`software.sava:json-iterator`, versions `21.x`),
  Java 21 baseline, no incubator modules. This is what production (sava) uses.
- **`feature/vectorize`** — an **experimentation/research branch**: JDK 27 EA +
  `jdk.incubator.vector`, plus new API (`IndexedJsonIterator`/`index()`, `at()` JSON
  pointer, `JIUtil.minify`, UTF-8 validation). It is *not* required to beat main on every
  workload; on 128-bit NEON it often loses (see Performance truths). The bet is that
  Vector API performance improves over time (especially post-Valhalla). Bug fixes are
  kept aligned between branches file-by-file to ease future backports — when fixing a bug
  on one branch, port it to the other in that branch's idiom, and prefer identical text
  where the code is shared lineage.

## Build, test, benchmark

- Tests: `./gradlew :json-iterator:test` from the **repo root** (running gradlew from
  subdirs has repeatedly caused wrong-directory failures; prefer `gradlew -p <root>`).
- The test suite is factory-parameterized (`TestFactories#FACTORIES`): byte array, char
  array, indexed byte array, indexed char array (main: byte, char, input stream). Every
  behavioral change must hold on **all** factories — the recurring bug class here is
  divergence between byte/char/stream paths (null handling, escape decoding, base64).
- Benchmarks: standalone composite build in `jmh/` (includeBuild substitutes the local
  project for `software.sava:json-iterator`). Build with `gradlew -p jmh jmhJar`, then run
  the jar directly **with explicit flags** — the jar does NOT inherit the gradle jmh{}
  defaults; without flags JMH runs 5 forks × 10s iterations (hours):

  ```
  java --add-modules jdk.incubator.vector -Dorg.simdjson.species=256 \
    -jar jmh/build/libs/json-iterator-jmh-jmh.jar "<regex>" -f 1 -wi 5 -w 1 -i 8 -r 1
  ```

  Use the **absolute jar path** (background shells reset cwd) and **rebuild jmhJar after
  every source change** — stale-jar runs have twice produced numbers for the wrong variant.
- The published baseline (`21.1.0`, shade-relocated to `jsoniter.v21`) is benchmarked
  alongside as `*_jsonIterator21`; its gradle configuration opts out of includeBuild
  substitution. simdjson-java 0.4.0 comes from Maven Central; it requires 256/512-bit
  species, so on NEON it runs emulated via `-Dorg.simdjson.species=256` and its numbers
  are unrepresentative (~100×) — omitted from README comparisons for that reason.
- Benchmark setup methods cross-check all implementations for identical results; a
  checksum mismatch at setup is a correctness bug somewhere, not benchmark noise.

## Discipline: measure, then keep

Every optimization on this repo is decided by JMH on the change in isolation: implement →
full test suite → benchmark → keep only measured wins, revert losers (several plausible
ideas measured negative: SWAR container skipping on main, two-block stage 1, vectorized
ISO-date validation, disabling SWAR readLong). One noisy fork is not a result — rerun with
`-f 2`/`-f 3` when Error exceeds a few percent; this machine (Apple M-series) gets noisy
under sustained benching. JMH reports Score ± Error (99.9% CI): compare Scores, use Error
for overlap. A local win is still hardware-conditional — see "Hardware strategy" below for
what qualifies it to be committed.

## Performance truths (measured on 128-bit NEON, Apple M-series)

These will shift on AVX2/AVX-512 — an open task is easy benchmarking on 256/512-bit lanes
(cloud x86 or Graviton 3/SVE; the jmh jar is self-contained).

- **`mask.toLong()` is the enemy.** NEON has no `movmsk`; bit extraction is a multi-op
  cascade. Idioms that won, in order of discovery: `anyTrue()` as the loop test (clean
  chunks skip extraction), `firstTrue()` + one scalar byte inspection at the returned lane
  for hit handling (the byte identifies *which* stop was hit — the bitset is never
  needed), one OR-combined mask instead of per-marker masks (skipContainer went 3
  extractions → 1).
- **Short strings dominate real JSON** and pay vector fixed costs per string. The string
  scans use a SWAR prefix (`SWAR_PREFIX` in BytesJsonIterator, currently 32 bytes) before
  entering vector chunks. The 16-vs-32 trade-off interacts with hit cost: after
  `firstTrue()` cheapened hits, 32 won broadly (twitter −15%, dates, fees) at a small
  long-string cost. Re-sweep this constant when hit-path economics change or on wider lanes.
- **Dense-hit loops don't reward wide scanning**: structural container walking has a
  marker every few bytes; scalar/SWAR wins there (measured on both branches).
- **Scalar beats vector for fixed small work**: 12-digit ISO-date validation and
  eight-digit SWAR readLong vs plain scalar loops are ties-or-losses; M-series scalar IPC
  is very high.
- **Stage 1 (`StructuralIndex`) is capped ~2 GB/s on NEON** by 12 `toLong()`s per 64-byte
  block; the C++ simdjson NEON kernel's `vpaddq` mask-reduction tree is not expressible in
  the Vector API. Not fixable here; first thing to re-measure on wide lanes.
- **`compress()` has no NEON lowering** — "horribly slow" Java fallback (confirmed by
  Emanuel Peter, JDK vectorization engineer; his blog https://eme64.github.io/blog/ is the
  authoritative source for these idioms). JsonMinifier uses run-based copies instead.
- Current standings vs published 21.1.0 on this hardware: 21.1.0 leads short-string and
  skip-heavy workloads (twitter walk, fees, blockParse, skipHeavy); this branch leads long
  uniform runs (long strings ~1.5×, minify ~1.9×) and is at parity on Solana full walks,
  doubles, and dates. `IndexedJsonIterator` wins selective access over large documents but
  its stage-1 fixed cost (≈2.4ms for 4.7MiB) makes it lose read-everything workloads.

## Hardware strategy: experiment locally, commit generally

- **Tuning against the local machine is explicitly allowed — even encouraged — as a
  temporary research tactic.** "Prematurely" optimizing for 128-bit NEON to see what the
  hardware rewards is how every idiom above was discovered. Do it freely in the working
  tree, measure, learn.
- **But NEON is one point in the design space, not the target.** Idioms that win here can
  lose on AVX2/AVX-512/SVE and vice versa — mask extraction is a single `movmsk`-class
  instruction on x86, `compress()` is native on AVX-512-VBMI2/SVE, and wider lanes shrink
  per-chunk fixed costs. Treat every local result as hardware-conditional until measured
  on wide lanes.
- **What gets committed to this branch should stay general.** Prefer strategies defensible
  across lane widths; put hardware-sensitive choices behind named, re-sweepable knobs
  (`SWAR_PREFIX`, `VectorSupport.BYTE_SPECIES`) rather than baking them into loop
  structure; and label each measured decision with the hardware it was measured on
  (comments here and in code). Runtime strategy selection by species width (128-bit →
  SWAR-leaning, ≥256-bit → wide-vector) is the eventual general mechanism.
- **For aggressive hardware-specific tuning, cut a target branch** off this one (e.g.
  `vectorize-neon`, `vectorize-avx512`, `vectorize-sve`) instead of committing
  machine-specific tuning to the shared research line. Same base, one variable — that
  keeps cross-hardware A/B comparisons honest, and a winning strategy gets promoted back
  only once it's measured on more than one target (or gated behind a species check).

## Correctness landmines (each has bitten)

- **Silent-wrong-answer bugs cluster in per-byte/word/vector scanning paths**: the
  published 21.0.12 corrupted multibyte strings (`containsMultiByteOrEscapePattern`
  matched exactly 0x80, not the high bit); base64 decoding misdecoded escaped input; chars
  `handleEscapes` stripped backslashes without decoding (`\t` → `'t'`). When touching scan
  loops, the police are the position-sweep tests (`test_escape_positions_across_vector_widths`,
  `test_long_utf8_strings`, `testDecodeBase64Robustness`, `test_skip_until_tricky_field_names`)
  — they walk escapes/multibyte/quotes across every word/vector/buffer boundary.
- **Multibyte lead bytes are `< 0` as signed bytes**; `(c ^ '\\') < 1` matches backslash
  OR high bit. Bytes exactly `0x80` appear mid-character in common text (`…` = E2 80 A6).
- **Escape semantics**: `\/` is legal JSON and `/` is in the base64 alphabet (PHP escapes
  it); surrogate pairs arrive as two `\u` escapes and must pair-validate. Escape handling
  must be identical across byte/char paths (`BytesJsonIterator#parseMultiByteString` is
  the reference; `CharsJsonIterator#handleEscapes` mirrors it).
- **javac processes `\u` escapes inside comments** — writing `\uXXXX` in a comment breaks
  compilation.
- **String-building in tests**: `'"' + i` is char+int arithmetic, not concatenation; it
  built garbage documents that passed for the wrong reason.
- JSON literals are validated on skip (`skipNull/skipTrue/skipFalse`) — `nulL`/`folse`
  throw; this was a deliberate strictness change in 21.1.0.
- `readDateTime` rejects >9 fractional digits (would roll into seconds / overflow int).
- `parse(InputStream)` reads to EOF and closes the stream eagerly; there is no buffered
  streaming (`BufferedStreamJsonIterator` was deleted; `bufSize` parameters are deprecated
  no-ops). On this branch `JsonIterator` no longer extends `Closeable`; main keeps it with
  a deprecated no-op `close()` until the next major.

## Style and process

- Doubles/floats parse via the shared Eisel–Lemire `DoubleParser` (binary32 is
  parameterized separately — narrowing a parsed double double-rounds; the float exponent
  cutoff must be −65, not −63: fast_float#167).
- The user commits everything themselves (signed) — leave changes unstaged, never commit
  or push. Report per-change so commits can be split.
- Match main's exact code/comments wherever the logic is shared, to keep future merges
  conflict-free; intentional divergences (public `applyCharsAsDouble`, `EMPTY_CHARS`,
  vector implementations, no `Closeable`) are known and should not be "fixed".
- Benchmarks exist to answer questions: ones that only verified a now-settled,
  hardware-independent design choice get deleted (e.g. Base64Bench).
