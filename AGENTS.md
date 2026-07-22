# Agent Context — json-iterator (main)

Context for working on the published line of `json-iterator`. Read this before
nontrivial changes: it records decisions, closed doors, and correctness landmines that
are expensive to rediscover, and that the code alone does not explain.

## Layout

- `json-iterator/` — the library (module `systems.comodal.json_iterator`). The target
  Java version is `javaVersion` in `gradle/sava.properties` — the single source of
  truth, consumed by the `software.sava.build` convention plugin. Change it there, not
  in a build script.
  `JsonIterator` is the public interface; `BaseJsonIterator` holds the scan/parse
  engine, with `BytesJsonIterator` / `CharsJsonIterator` supplying the source-typed
  reads. `FieldMatcher` is the hash-based field/value dispatch surface. The rest of the
  package is the inversion-of-control functional-interface family (`*BufferPredicate`,
  `*BufferFunction`, `Context*`, `FieldIndex*`) that lets consumers parse without
  allocating per field.
- `jmh/` — standalone Gradle build (`includeBuild("..")`, so it always benches local
  sources). `IocBench`, `MigrationBench`, and `SourceBench` over two real documents.
  Its `README.md` holds the FieldMatcher migration decision table and the measurement
  methodology — that table is the justification for the current deprecations, so read
  it before touching the dispatch APIs.
- Releases are automated: conventional commits → release-please (`always-bump-patch`,
  no `v` prefix in tags) → publish. Don't hand-edit versions or `CHANGELOG.md`.

Build and test with `./gradlew check`. Dependencies resolve from GitHub Packages, so a
`savaGithubPackagesUsername` / `savaGithubPackagesPassword` pair is needed in
`~/.gradle/gradle.properties` (see `README.md`). Beyond `check`, the hardening tasks
(sava-build's `hardening` extension in `json-iterator/build.gradle.kts`): PIT mutation
suites `pitestIterator` / `pitestNumbers` / `pitestUtil` (reports under
`build/reports/pitest/<suite>/`) and the Jazzer fuzz targets described under
correctness landmines. The PIT baselines are fully triaged — every accepted
mutant has a written reason in `json-iterator/config/pitest/README.md`, with no
untriaged debt.

## Verification & the mutation ratchet

The process contract for changes here (full policy: sava-build's `HARDENING.md`):

<!-- This section adapts the agent-instructions template in sava-build's
     HARDENING.md; `agentsTemplateInSync` (wired into `check`) fails when the
     template changes until the block is re-diffed — sync or ACT on each changed
     bullet (a new bullet may need code, not prose) — and the digest updated. -->
<!-- hardening-template sha256:96ddf18dcc3a -->

- **Scale verification to the change; suite choice is reachability, not habit.**
  Iterate with `test` (or `--tests` for the touched classes). Before handing off,
  run only the `pitest<Suite>`(s) whose mutated code the change can reach:
  `pitestNumbers` (DoubleParser) and `pitestUtil` (JHex/JIUtil/InstantParser/
  FieldMatcher) finish in ~10s; `pitestIterator` (everything else) costs ~90s.
  Test-only edits still owe the suite those tests kill mutants in — a weakened or
  deleted test is exactly what the ratchet catches; doc, build-script, and comment
  changes owe no suite at all.
- **`qualityGate` — every suite, serialized, each diffed against its baseline in
  `config/pitest/` — is the pre-release check, not the inner loop or the per-commit
  gate.** The pre-release ritual is `qualityGate` plus long fuzz runs
  (`-PmaxFuzzTime=600`+) plus a change-scoped jmh A/B per benchmark discipline
  below. CI deliberately runs only `check` (sava-build's shared workflow): the
  serialized PIT suites are too slow for hosted runners, so the full gate is
  **owned by the local release checklist** — run before deciding to release, not
  by CI. Don't wire `qualityGate` into CI to "fix" this; it's a decision.
- A new unkilled mutant has exactly three legal outcomes: **kill it** with a test
  (prefer asserting the property it breaks — position after a skip, exact error
  context, allocation bounds — over restating the implementation), **refactor** it
  out of existence, or **accept it** with a written reason in
  `config/pitest/README.md`. Never run `-PupdateMutationBaseline` just to make the
  build pass. When a claimed equivalence spans a sweepable domain, verify it
  empirically — reimplement both variants, diff them over the range, record the
  range in the note; a sweep survives refactors that rot a prose argument.
- **`SURVIVED` and `NO_COVERAGE` are different problems.** The first means a test
  executed the line and couldn't tell the difference — a judgment call between a
  stronger assertion and a written acceptance. The second means no test reached the
  line — mechanical work, and never acceptable as "equivalent", because unobserved
  behavior can't be judged equivalent. Suite runs print the split; read it before
  planning a pass. A third case exists for `SURVIVED`: distinguishable in
  principle but unreachable through any deterministic harness (an HTTP 1xx the
  JDK client never surfaces as a final status) — accept as **unreachable
  in-harness**, naming what would reach it, never as "equivalent".
- Line-number churn from editing a mutated file shows up as paired stale + "new"
  baseline entries; confirm they're the shifted old ones before refreshing.
- **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
  detected and is not written to the baseline, and it is load-dependent: the same
  mutant can report `SURVIVED` when its suite runs alone and `TIMED_OUT` under
  `qualityGate`, so the build flips on how it was invoked. Verify a changed
  baseline in both modes; union in only rows observed to flip, never every
  `TIMED_OUT` row; prefer removing the cause (a call budget or bound in the
  harness) over leaning on the timeout.
- **Randomized tests use fixed seeds, and never sleep** (`TestDouble`,
  `TestString`): the ratchet needs deterministic kills, and PIT re-runs the
  covering tests once per mutant, so a single real wait costs minutes across a
  suite. Per-run exploration is the fuzz targets' job — don't reintroduce
  `new Random().nextLong()` seeding.
- **A flaky harness is worse than recorded debt.** If an interleaving or boundary
  cannot be made deterministic, accept the mutant with a written reason rather
  than chasing it with sleeps, spin-waits, or thin-margin bounds.
- **A wandering unkilled count is a defect to chase, not re-ratchet past** — the
  baseline records whichever run wrote it, so a lucky run bakes in a row later
  runs fail on. Beyond `TIMED_OUT` flips (above) and unseeded randomness, two
  observed causes elsewhere in this process: `@Execution`/`@TestInstance` failing
  to reach concrete test classes from an abstract base — version-dependent:
  JUnit 6 marks both `@Inherited`, and `@Execution` is moot without parallel
  execution; `javap` the resolved jar before restructuring — and
  coverage attributed to a field or static initializer is unstable — exercise
  factories from inside a `@Test`. Converged means consecutive runs agree on the
  per-mutator sub-totals, not just the headline number.
- **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
  arithmetic is method calls, invisible to `STRONGER`'s arithmetic mutators.
  Trialed `EXPERIMENTAL_BIG_INTEGER` on 2026-07-21 across all three suites:
  zero fires (1904/326/335 generated, unchanged) — the readers construct Big
  values from parsed chars but do no arithmetic on them. Left off; re-trial if
  Big arithmetic is introduced. Numbers in `config/pitest/README.md`.
- **A suite's percentage is not a target.** An accepted mutant with a written
  reason is finished work, not debt. Here every baseline entry is triaged in
  `config/pitest/README.md` with no untriaged debt. Attention belongs to a
  growing baseline, not a number below 100%.
- **Allocation bounds are asserted where allocation is the contract — and only
  there.** `TestAllocation` pins the zero/exact-allocation contracts via
  `ThreadMXBean` allocation counters; new API whose *stated design goal* is
  allocation behavior gets the same treatment, and that is also how
  otherwise-"equivalent" grow/trim mutants get killed. But the harness is a last
  resort, not a default: it re-runs once per mutant, every measured result must go
  to a `static volatile` sink so escape analysis can't delete the allocation under
  test, and a thin margin (observed: mutant at 88 bytes vs a 90-byte bound) is a
  flaky harness with extra steps. An incidental micro-optimization that only an
  allocation bound could observe is a mutant to accept, with "allocation routing
  only" as the written reason.
- **When a test you believe in will not go green, suspect the code before you
  soften the assertion** — that is where this process finds real bugs, not in the
  mutant kills themselves.
- **Suite exclusions must cover the test source set, not a naming convention.**
  All test-source classes here are `Test*` or `*Fuzz*` today, and the `iterator`
  suite excludes those patterns — but a shared fake extracted to a top-level
  `RecordingFoo`/`StubFoo` matches neither and silently joins the mutated
  population. `pitest<Suite>Verify` cross-references mutated classes against the
  test source set and warns by name; heed that warning, and after registering or
  widening a suite confirm no mutated class lives under `src/test`.
- **Verify by the absence of failures, not the presence of passes.** Counting
  `PASSED` lines hides a failure sitting next to them, and a green `clean build`
  can mean the build cache short-circuited rather than that tests ran. Check the
  failure count and confirm the task actually executed. A mutation run has a
  second version of this: a *failed* PIT run leaves the previous run's report in
  place, so the summary you read can describe a run that never happened — trust
  the exit code, and delete report directories when comparing runs.
- **A suite that got faster without getting narrower is a bug report.** Real
  speedups come from fewer mutants or faster covering tests; an unexplained one
  usually means the run did less than you think. (Exception if arcmutate history
  is ever activated here — `arcmutate-licence.txt` at the repo root, free for
  OSS: a `[history]` marker on the summary makes fast the expected state, and
  the pre-release gate then runs `-PnoMutationHistory` to re-earn every status
  from scratch.)
- **Transient infra failures are not results.** PIT `MINION_DIED` fails before
  writing a report, so it cannot corrupt one — re-run the suite; a Gradle-worker
  `EOFException` death is the same shape, and a per-mutant `RUN_ERROR` under
  load is the same shape smaller (the summary names it, not counted as
  detected). The daemon log
  (`~/.gradle/daemon/<version>/daemon-<pid>.out.log`) keeps a failed build's
  full output even when the shell discarded it — read it before calling a
  failure unexplained.

## Correctness landmines

Every one of these has bitten this codebase. The silent-wrong-answer bugs cluster in the
per-byte and per-word scanning paths — the published 21.0.12 multibyte corruption,
base64 escape misdecoding, and `handleEscapes` stripping escapes without decoding them
on the chars path. A scan-path change that passes a smoke test can still be badly wrong.

- **The police for any scan-path change** are the position-sweep tests:
  `test_escape_positions_across_vector_widths` and `test_long_utf8_strings`
  (`TestString`), `testDecodeBase64Robustness` (`TestString`), and
  `test_skip_until_tricky_field_names` (`TestObject`). They walk stops across every
  offset in a window rather than testing one happy path. If you touch string scanning,
  escape decoding, base64, or `skipUntil`, these are the tests that will catch you —
  and if you add a new scan path, it needs a sweep of its own.
- **The heavier police are the differential fuzz targets** — `fuzzJson` (byte vs char
  sourced iterators must produce identical event streams or both reject), `fuzzDouble`
  and `fuzzNumber` (JDK bit-equality oracles), `fuzzInstant`. Run the relevant one for
  a few minutes after any scan or parse change:
  `./gradlew :json-iterator:fuzzJson -PmaxFuzzTime=120`. The harness contract is
  strict — `JsonException` (plus `DateTimeException` for instants) is the only
  accepted rejection on any source, so crash-class regressions (bounds faults, stale
  reads past `tail`) surface as findings, not noise. Every finding becomes **two**
  artifacts: the minimized input promoted into the seed corpus
  (`src/test/resources/fuzz/<target>/regression-*`, replayed deterministically by
  `TestFuzzCorpusReplay`) *and* a named regression unit test asserting the fixed
  behavior. A crash fixed without both is a crash that can return.
- Multibyte lead bytes are **negative** as signed `byte`s, and `0x80` appears
  mid-character in ordinary text — neither is a safe sentinel.
- **Never index a lookup table with a raw source value.** A signed byte is negative on
  multibyte content and a char exceeds any byte-sized table, so `TABLE[c]` faults with
  the wrong exception class instead of rejecting. One fuzzing session found this in
  three separate tables (digit resolution, `VALUE_TYPES`, `JHex`); the surviving
  shapes are range-checked arithmetic (`BaseJsonIterator.intDigit`) or a guarded
  accessor (`ValueType.of`) — use those, don't add a fourth table.
- `\/` is legal JSON, and `/` is in the base64 alphabet. Escape handling and base64
  decoding interact.
- Surrogate pairs arrive as two separate `\u` escapes and must be validated as a pair.
- `javac` processes `\u` escapes **inside comments** — a `\u` in a comment can break the
  build or, worse, change a string literal's meaning.

## Settled design decisions — don't re-propose without new evidence

**Library javadoc never names downstream consumers.** This is a core library; its
public docs must not reference consumer projects or their types (a `JupiterPrice`,
a sava RPC parser) — users of this library don't know or care about its other
consumers, and such references rot silently when the consumer changes. Javadoc
examples use generic placeholder types (`Entry::parse`, `Token::address`,
`KEY_PARSER`). The one place consumer names belong is `jmh/README.md`'s migration
notes, where sava/idl-src-gen are the measured subjects.

**The known consumers are not the only consumers.** This library is published to
Maven Central; unknown users exist. The local repos (sava, idl-src-gen, idl-clients,
…) are a survey sample that informs API priorities — they are not an inventory of
usage. "No local consumer calls this" justifies a deprecation cycle, never an
outright removal or a silent behavior change; and workload assumptions measured from
local consumers (all-`byte[]` input, field-name lengths, dispatch widths) are
defaults to optimize for, not invariants to depend on for correctness.

Removing deprecated surface follows the same doctrine, as a procedure: a
`@Deprecated(forRemoval = true)` marker must ride at least one published release,
and removal is cleared per member by re-running the consumer sweep — grep every
jsoniter-importing `.java` file under `~/src` (skip `forks/`, build output, and dead
code like idl-clients-drift), then weight each hit by its repo's last commit date.
Zero hits or dormant-only hits (repos untouched for a year or more) clear removal;
any actively-committed consumer blocks it until those call sites migrate. Do not
trust a stale survey — the sweep is cheap, repo activity changes, and the hit list
rots faster than this file is edited.

**`FieldMatcher` fields sit directly above the predicate that consumes them**, not at
the top of the class with the other static fields. The matcher's declaration order
defines the `case` indices of the switch that dispatches on it — that coupling is
positional and silent, so the two must be readable (and reviewable) as one unit.
This deliberately overrides the fields-first class layout convention; it applies to
migration examples in these docs and to consumer parsers written from them.

**Dispatch API verdicts** live in `jmh/README.md`'s decision table, measured with the
full suite. The headline: `FieldMatcher` wins big on large unions (~40% at 37–52 names)
and on kind/discriminator dispatch (~10% and zero allocation), but the char
`fieldEquals` **chain is deliberately not deprecated** — it is the fastest option below
roughly 8–10 names. Don't "modernize" small enums onto the matcher for performance;
there isn't any.

**Docs carry current state only.** When a surface is removed, delete its
documentation — decision-table rows, migration examples, standing-task mentions —
rather than annotating it as removed; git history is the archive. The exception is a
rationale that still justifies something present (e.g. a PIT acceptance reason for a
baseline entry, or why an API deliberately does not exist).

**Data source: feed `byte[]`.** Measured in `SourceBench`; the table is in
`jmh/README.md`. The rule matters more than it looks, because the cost of feeding a
`String` is a *UTF-8 encode*, not a copy: `parse(String)` routes through
`String.getBytes()`, and any non-ASCII content forces a UTF-16-backed String, which
makes that call 4× more expensive per byte than the compact-String case. Measured
penalty over `byte[]`: **2.49× on the 15%-non-ASCII twitter document**, 1.32× on the
pure-ASCII solana one. So: don't build the String, parse the bytes off the wire.
`parse(String)` is a convenience for tests and the REPL.

Two corollaries that are easy to get backwards:

- **Iterator reuse buys nothing at document scale.** A fresh `parse(byte[])` per
  document ties `reset(byte[])` on a 600 KiB document and costs ~2% on a 4.7 MiB one.
  `reset()` is worth reaching for only at high rates of *small* documents.
- **If you already hold a `String`, feed `toCharArray()`, not `byte[]`** — unless the
  content is known pure ASCII. This is measured, and it is the opposite of the obvious
  advice: on the UTF-16-backed twitter document the char route wins **2.03×** (560 vs
  1139 µs). Each String pays whichever conversion runs against the grain of its own
  backing — a UTF-16 String yields chars for 65 µs and bytes for 691 µs (a transcode);
  a Latin-1 String yields bytes for 1308 µs (a near-memcpy) and chars for 901 µs (an
  inflate), which is why `getBytes()` edges it there by ~7%, within noise. **Never
  repeat "narrow to bytes once" as general advice** — it holds only when the bytes never
  became a String in the first place.
- **There is no incremental parse, and the stream source's cost is GC, not latency.**
  `parse(InputStream)` and `reset(InputStream)` both call `readAllBytes()` and iterate
  the resulting array. `-prof gc` prices it exactly: one full-document copy allocated
  per call (+631,528 B/op on the 631,515 B document; +4,755,968 B/op on the 4,755,919 B
  one). The trap is that this is nearly **invisible in the parse timing** — the latency
  cost is only **+3.1%** (identically on both documents, same-session control) while the
  allocation rate goes 22× and GC time 43×. Judge the stream API by
  `gc.alloc.rate.norm`, not by the score; in a long-running service the collector pays
  what the benchmark doesn't.

**Three features were reviewed for promotion in 2026-07 and rejected on the merits.**
They exist, fully implemented, on the `vectorize-archive` tag. Don't resurrect them
without consumer demand that didn't exist then:

- *`IndexedJsonIterator`* (simdjson-style structural pre-index) fails **structurally**
  with a scalar backend: scalar stage-1 classification of every byte is slower than the
  plain iterator's SWAR skips (14.3 ms vs 2.3 ms on a 4.7 MiB document), so any
  single-pass workload — which is all of them — loses before reading a value. Only
  parse-once-query-many would win, and no consumer has that shape.
- *`at()`* is a per-call-allocating alias for a `skipUntil` chain.
- *Minify* serves an audience this library doesn't have.

## Closed doors on vectorization

These are **workload-structural** — no wider vector hardware and no future JDK reopens
them. They are conclusions, not open questions:

- **Never vectorize the `char[]`-source paths.** Not because vector code loses there —
  it wins. The path has no users: every surveyed consumer feeds `byte[]`. So the trade
  is complexity and scan-path correctness risk (the silent-corruption bugs above all
  live in exactly this kind of code) bought with speedup that no consumer executes. And
  the door stays shut even if a `char[]` consumer appears: 16-bit lanes hold half as
  many elements, so a vectorized char path is permanently capped near half the byte
  path's throughput. The measured scalar char tax is already 8% (twitter) to 18%
  (solana) — `SourceBench` — and vectorizing cannot close a gap it inherits. Recorded
  so it isn't re-proposed on the assumption it was never tried: the vector base64
  narrowing on the chars path measured a real 3–6% end-to-end win, and was reverted
  anyway.
- **Never vectorize field-name scanning.** Surveyed names average 12.5 bytes and top out
  around 34; a 32-byte SWAR prefix covers essentially all of them before a vector chunk
  would engage.
- **Never vectorize dispatch comparison.** `FieldMatcher.match` is two word loads and a
  mix. Chains win below ~8–10 names and the hash wins above; no regime leaves room for a
  vector compare of ≤34-byte names.

Everything else vector-related is **research, and it does not live on `main`.** The
`vector-lab` branch holds the kernels and benches (its library sources are byte-identical
to main by invariant, and its charter is `VECTOR-LAB.md`, a file that exists only on that
branch — this `AGENTS.md` is common to both); the retired long-lived fork is the
`vectorize-archive` tag. If you want to land a vector optimization here, the process is:
prove the kernel on
`vector-lab`, then cut a **short-lived branch off `main`**, wire it in, and judge it with
the full suite. Kernel wins do not survive integration automatically — the string-scan
hybrid won 18–38% at kernel level on exactly the string lengths this library sees, then
lost 7–16% across the board once integrated, and that branch was deleted.

## Benchmark discipline

**Always `-PjmhFork=3` or more, and always run in isolation.** Single-fork results swing
10–20% on JIT inlining luck alone on this codebase — a real 21% win has measured as a
13% loss in one fork. Concurrent builds or a second benchmark run on the same machine
inflate error bars enough to flip close verdicts. Treat any row whose error exceeds ~10%
of its score as contaminated and re-run it alone.

```sh
./gradlew -p jmh jmh -PjmhFork=3 -PjmhIncludes=kindDispatch
```

Runs are archived timestamped under `jmh/jmh-results/` (outside `build/`, so `clean`
cannot erase measurement history), and `build/results/jmh/results.txt` is re-rendered
after each run as a newest-wins merge across all archived runs — so subset runs converge
on a full scoreboard, but rows can be of mixed vintage. Delete an archive file to drop a
bad run's rows. Every benchmark cross-checks its variants' checksums at `@Setup`; with
`-foe true` a disagreement is a hard failure, not noise.

**Scope the run to the change; don't default to the full suite.** A full-suite
`-PjmhFork=3` run costs hours; a change-scoped `-PjmhIncludes` subset gives
decision-grade rows in minutes (the 2026-07 scan-path A/B ran
`SourceBench.(bytes_reset|chars_reset),IocBench.blockParse` — every touched hot path —
in under five minutes per side). Pick rows by which paths the change touches:
`bytes_reset`/`chars_reset` isolate the two source walks, `blockParse` adds real
`readLong` volume, the dispatch rows only matter for `FieldMatcher`/chain changes.
Reserve the full suite for API-shape verdicts that feed the `jmh/README.md` decision
table.

A same-session control run matters more than a historical baseline: when judging a
change, measure `main` and the change back-to-back on the same machine in the same
session. Comparing against numbers from a prior day has produced wrong verdicts — the
"10–20% InputStream tax" briefly written into `jmh/README.md` was an artifact of
comparing a row against a *different run's* baseline. Pinning that one number down took
**four runs**; the true figure is +3.1%, and the intermediate answers were +19%, +21%,
0%, and +1.4%. Every wrong one came from a row whose error bar was over the 10% rule, or
from a control measured in another session.

"Isolation" includes the machine, not just your own processes. Check `uptime` before
trusting a run: Spotlight indexing and a couple of open JetBrains IDEs have inflated
`bytes_reset` on the solana document by 23% (3899 → 4818 µs) on identical code, with
error bars to match. Note this is invisible without the control row — the absolute
number looked plausible. Contamination also moves between rows run to run (it hit
`stream_reset` in three sessions, then `bytes_reset` in the next), so a row being clean
last time is no reason to trust it this time.

Do not invent a mechanism to explain a contaminated row. The bogus solana stream tax got
a confident, plausible explanation — a "large-object ZGC allocation path" — that was pure
fiction, and it survived precisely because it sounded like an answer. If the error bar is
over the threshold, there is nothing to explain yet.

Allocation is measurable even when the machine is noisy, and it can be the whole story:
`-prof gc` reports `gc.alloc.rate.norm` (bytes per op) to ~0.01 B/op regardless of CPU
contention. Reach for it whenever an API might be paying in GC rather than in latency —
the stream source ties on score and allocates 22× more.
