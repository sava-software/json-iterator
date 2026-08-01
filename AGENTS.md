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

To build against an unpublished sava-build change, publish sava-build to its local test
repo and point this build (or `jmh/`) at it — the property belongs in
`~/.gradle/gradle.properties` or on the CLI, never in `settings.gradle.kts`:

```sh
(cd ../sava-build; ./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository)
./gradlew check -PsavaBuildLocalRepo=../sava-build/build/sava-test-repo
```

While set, every `software.sava.build*` plugin id resolves to the `0.0.0-test` module
from that repo and the pinned versions are ignored (a warning is logged). **The publish
is not automatic** — re-run it after every sava-build edit, or the build silently keeps
the previously published jar. When done, drop the property and bump the pinned versions
(the `plugins {}` block in `settings.gradle.kts` *and* the `feature.jmh` version in
`jmh/build.gradle.kts`) to the released sava-build.

## Verification & the mutation ratchet

The process contract for changes here (full policy: sava-build's `HARDENING.md`).
What a clean gate does *not* prove — the mutator set, the class path, the
`targetTests` boundary, the excluded classes — is inventoried in
`HARDENING_NOTES.md`; read it before reporting a green run as coverage.

<!-- This section adapts the agent-instructions template in sava-build's
     HARDENING.md; `agentsTemplateInSync` (wired into `check`) fails when the
     template changes until the block is re-diffed — sync or ACT on each changed
     bullet (a new bullet may need code, not prose) — and the digest updated. -->
<!-- hardening-template sha256:e035f8cc1fec -->

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
  `config/pitest/README.md` **and a short family label on the row itself**
  (`# slow-path routing`) — refreshes seed every genuinely new row `# untriaged`,
  and triage means replacing that label, so the baseline says which rows are
  argued and which are debt. The verify and `pitest<Suite>Debt` count rows per
  label and warn when a family label has no `# <label>` mention in
  `config/pitest/README.md` (`# untriaged` exempt). These baselines' rows predate
  label support (notes 21.5.10, refresh seeding 21.5.12) and print as
  `unlabeled` — that is recorded state, not
  new debt; label a row with its README family when you touch it, never by bulk
  inference (a misassigned label reads as finished triage). Never run
  `-PupdateMutationBaseline` just to make the build pass. When a claimed
  equivalence spans a sweepable domain, verify it empirically — reimplement both
  variants, diff them over the range, record the range in the note; a sweep
  survives refactors that rot a prose argument.
- **`SURVIVED` and `NO_COVERAGE` are different problems.** The first means a test
  executed the line and couldn't tell the difference — a judgment call between a
  stronger assertion and a written acceptance. The second means no test reached the
  line — mechanical work, and never acceptable as "equivalent", because unobserved
  behavior can't be judged equivalent. Suite runs print the split; read it before
  planning a pass. A third case exists for `SURVIVED`: distinguishable in
  principle but unreachable through any deterministic harness (an HTTP 1xx the
  JDK client never surfaces as a final status) — accept as **unreachable
  in-harness**, naming what would reach it, never as "equivalent". Before
  accepting any survivor, ask whether the mutated line still *executes*: PIT
  reuses minion JVMs across mutants, so process-lifetime state left by an
  earlier mutant's run of the same test is still there, and a memoizing
  `computeIfAbsent` over a static cache — keyed by a constant the test
  hard-codes — never re-invokes the mutated lambda again. An unkillable mutant
  on a cache-miss path is a **fixture bug until proven otherwise** (mint a
  fresh key per invocation), and it reads exactly like equivalence, because
  "no test can observe this" is the same sentence either way. Nothing here
  memoizes today — `JHex$INIT_DIGITS` is a static table with its own family
  argument, not a cache — so this binds any future one.
- **Baseline keys are line-less** — `class,method,mutator,STATUS`, with each
  row's observed line demoted to a trailing `# line N` tag that every refresh
  rewrites. Editing above a mutated method churns nothing the ratchet sees,
  so the whole drift apparatus is retired: no shift classifier, no tolerated
  pure-drift pass, no `PAIRING OUTLIER` scan, no `-PnoDriftTolerance` (the
  2026-07-26 mispaired-shift incident is dead by construction, not by
  classifier). These baselines were migrated 2026-08-01 with
  `migrateMutationBaselines` (parse/re-render, no mutation run, identities
  unchanged: 143/76/43). Migration is **one-way** — a pre-21.5.20 plugin
  reads a migrated file as every row stale plus every mutant new — so both
  pins (`settings.gradle.kts`, `jmh/build.gradle.kts`) move together.
  Two paired stale + "new" classifications remain and want opposite
  responses: `(newly covered — was NO_COVERAGE)` is triage, since refreshing
  there launders a fresh survivor into the baseline, and `(shares an accepted
  key — sibling debt surfaced, or a NEW mutant at that key; check the line)`
  needs the report's lines read before anything is accepted. An *unexplained*
  row is a genuinely new key; an extract-method refactor lands there
  deliberately, the key naming the method and the covering tests at the
  mutant's new home differing.
- **The line-less trade is one documented hole: a same-key swap is invisible.**
  Kill a mutant and introduce another at the same
  `class,method,mutator,STATUS` in one change and the multiset is unchanged —
  the new mutant silently inherits the old row's acceptance. The only trace is
  the **line-drift advisory** (a key unkilled at lines no `# line` tag names),
  so when it names a key whose `config/pitest/README.md` argument no longer
  reads against the code, treat it as that swap until shown otherwise. Expect
  a wave of these on the first runs after the migration: legacy line fields
  were allowed to lag (pure drift used to pass with a notice), and promoting
  them to tags surfaces the lag as re-read prompts, not regressions.
  `-PlistUnkilled` prints unkilled rows with PIT's mutation description
  prefixed by its line (`line 41: removed conditional…` — the key no longer
  carries one). Refreshes carry row notes within a key by **line affinity**
  first, then file order, annotating a status flip
  `(carried across NO_COVERAGE -> SURVIVED)` — re-read those, an argument for
  an unreached mutant isn't automatically one for an observable mutant — and
  the dropped-rows listing names each note's fate and counts the losses. Rows
  parse as an ordered list, so duplicate sibling rows each keep their **own**
  note. A third, always-safe refresh exists: `-PpruneMutationBaseline` only
  drops rows matching nothing this run (keeps `TIMED_OUT` coordinates and
  cross-status unkilled ones, naming them) — prefer it after a killing pass
  over hand-rolled cleanup scripts, and note the three refresh flags are
  mutually exclusive. All baseline rewrites are atomic, so an interrupted
  refresh cannot truncate the file.
- **The PIT version is part of the record.** The mutant population is a
  function of PIT itself, which rides sava-build bumps, so
  `config/pitest/<suite>-pitest-version` records which version wrote each
  suite's baseline — per suite, because one shared file would lift every
  suite's refusal at the first refresh after a bump. It is stamped at the
  *successful end* of a baseline-writing run. A mismatch warns on a checking
  run and **refuses** every record-writing flag, `-PinitTimeoutAudit`
  included (the timeout population is just as version-dependent, but it never
  stamps). Bumping deliberately means setting the suite's file to the new
  version and refreshing that suite, reading the churn as a real population
  diff rather than noise.
- **Iterate with `-PmutateOnly=<class-glob>`** while killing a cluster —
  seconds instead of a full suite — then re-run unscoped before any refresh;
  scoped reports are stamped `.scoped` and every baseline-touching consumer
  (the ratchet, refreshes, mode snapshots) refuses them. `pitest<Suite>Debt`
  ranks survivors and no-coverage by class with the delta against the
  baseline — use it to pick the next cluster instead of re-deriving the
  ranking from the CSV.
- Identical baseline rows are sibling mutants of one compound condition and
  the comparison is a multiset — never hand-dedupe (these baselines carry
  such rows: `matchPattern`, `DoubleParser` scan guards, `INIT_DIGITS`).
  When one sibling survives, the verify names the killed sibling's test
  (`[detected sibling at this line: KILLED by <test>]` — in the failure
  listing, scoped runs, and `-PlistUnkilled` alike); the survivor is the
  opposite branch direction — triage it as its own mutant, not as the one
  that test was aimed at. The 2026-07-23 multiset re-triage is the
  proof it pays: two "siblings of accepted equivalents" were real
  lenient-literal-skip gaps, killed in `TestSkip` along with eight
  neighboring rows the exact-offset assertions swept up.
- **Do not rely on PIT's timeout to detect a mutant.** `TIMED_OUT` counts as
  detected and is not written to the baseline, and it is load-dependent: the same
  mutant can report `SURVIVED` when its suite runs alone and `TIMED_OUT` under
  `qualityGate`, so the build flips on how it was invoked. Verify a changed
  baseline in both modes; union in only rows observed to flip, never every
  `TIMED_OUT` row; prefer removing the cause (a call budget or bound in the
  harness) over leaning on the timeout. Prefer writing unions through
  `pitestModeCompare -PunionModeFlips`, which annotates each row
  `# flip insurance (<per-mode statuses>)` and tags the observed `# line`, so
  the note carries its own re-measurable evidence and the row-level drift
  advisory covers it. `-PunionMutationBaseline` remains the escape hatch for a
  directly witnessed flip (append-only; a full `-PupdateMutationBaseline`
  names every row it drops) — but it lands **bare** rows, so a hand union owes
  the evidence note by hand or the insurance is an unargued acceptance. The
  verify announces run-to-run status
  drift from a machine-local stash (`.pitest-history/<suite>.statuses`):
  `KILLED -> TIMED_OUT` is a benign count, `SURVIVED -> TIMED_OUT` a warning
  — never let a refresh drop such a row on the strength of a loaded run.
  (The verify's stale-entry hint knows this too: a baseline row whose coordinate
  read `TIMED_OUT` this run is reported as the load flip it is, not as a row to
  prune.)
- **A new timed-out mutant is a reviewer-stop, not detection noise** — for
  exactly these the ratchet cannot see a weakened covering assertion, since the
  timeout keeps "detecting" whatever the test asserts. Each suite's timeouts are
  an audited set: `config/pitest/<suite>-timeouts.csv` holds line-less
  `class,method,mutator` keys (`iterator-timeouts.csv` 6 rows,
  `util-timeouts.csv` 2; `numbers` has never timed out, so it has no file and
  the check is inert there), and `config/pitest/README.md` §"Timed-out mutants
  (audited set)" carries the structural cause per member, each naming the line
  it argues about. The verify warns on any timeout outside the set (paste the
  printed row, then write the cause), on rows that do not parse as three
  fields (named malformed, matched against nothing), on members matching no
  mutant, on members whose class and method appear together in no single
  README **section** (a markdown-heading block, each name matched as a whole
  word — paragraph granularity false-flagged causes written in the house
  style that names the class in a section intro and argues each method
  below), and — from a machine-local counter
  (`.pitest-history/<suite>.timeout-quiet`) — on members with no timeout in
  3+ consecutive mutation runs (membership must keep earning itself; a stale
  interlude resets the counter). It also parses the `# line` comment back and
  warns as **line drift** when a member's observed timeout lines are all
  absent from it (the anchor its cause argues about moved entirely; a new
  sibling line next to a recorded one stays quiet, that being the line-less
  key's stated resolution). Admit a newcomer only with its cause
  written. Every audit finding is advisory by default and re-printed in a
  one-line-per-suite end-of-build summary; for certifying runs,
  `-PstrictTimeoutAudit` escalates exactly the keep-the-audit findings
  (unaudited newcomer, malformed row, a member whose cause was never written
  — the doctrine admits a newcomer only with its cause, so a cause-less
  member is an unfinished admission, not hygiene — and timeouts with no set)
  to failures; hygiene findings (stale members, quiet streaks, drifted lines)
  stay advisory even there. `-PstrictTimeoutAudit` refuses a `-PmutateOnly`
  report outright, like the refresh flavours. The audit's static half (row
  shape, cause presence) also runs in `pitest<Suite>Debt`, so a pasted row
  or written cause is confirmed in seconds without a mutation run. Mind the
  resolution: line-less keys mean a *new* timed-out mutant inside an
  already-audited method+mutator draws no warning beyond the line advisory,
  so re-read the README's cited lines whenever that code changes.
- **Randomized tests use fixed seeds, and never sleep** (`TestDouble`,
  `TestString`): the ratchet needs deterministic kills, and PIT re-runs the
  covering tests once per mutant, so a single real wait costs minutes across a
  suite. Per-run exploration is the fuzz targets' job — don't reintroduce
  `new Random().nextLong()` seeding.
- **Time-dependent code takes a clock**, so tests advance time instead of
  waiting — and test clocks get a non-zero origin, since a clock starting at
  0 makes every "timestamp mutated to 0" mutant equivalent by accident.
  Nothing in this library reads a clock today (`InstantParser` parses input,
  it never asks for *now*); the rule binds any future surface that would.
  The non-zero-origin rule generalized: **any stub or fixture returns
  distinguishable, non-default values** — a canned null/0/`""`/true/empty
  makes the matching return-value mutant equivalent by accident of the
  fixture (no stubs exist in this suite today; binds any future fake). Same
  future-binding shape for **copy-on-write routing** (`size() > 1 ?
  unmodifiableCopy : as-is`): assert immutability of returned collections
  (`assertThrows(UnsupportedOperationException, ...)`) at every size — the
  mutable-escape direction is a kill, only content-equal siblings are
  family-accepted (no collection-returning API routes through unmodifiable
  copies here today).
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
  factories from inside a `@Test`, and build any subject under test inside
  the test body, not in a test field: under `PER_CLASS` lifecycle a
  field-initialized subject's construction coverage attaches to whichever
  test runs first, so its wiring mutants can never pair with the test that
  drives what they wire. Convergence is scripted: `pitestConverge`
  runs every suite twice and diffs per-mutant statuses (two runs can match in
  total while disagreeing about which mutants died — the headline number is
  not the check), and `pitestModeSnapshot -PpitestMode=<label>` /
  `pitestModeCompare` diff solo-vs-`qualityGate` modes, writing observed flips
  to the baseline with `-PunionModeFlips`. The compare keys line-lessly on
  `(class, method, mutator)` with statuses compared as sorted multisets per
  key — the baseline's own key shape, so a row it writes is one the verify
  recognizes, and per gated status a key needs as many rows as the widest
  mode observed (one row cannot insure two flipped siblings).
- **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
  arithmetic is method calls, invisible to `STRONGER`'s arithmetic mutators.
  Trialed `EXPERIMENTAL_BIG_INTEGER` on 2026-07-21 across all three suites:
  zero fires (1904/326/335 generated, unchanged) — the readers construct Big
  values from parsed chars but do no arithmetic on them. Left off; re-trial if
  Big arithmetic is introduced. Each `pitest<Suite>` run now looks for that
  blind spot directly, scanning the recompiled classes' constant pools for
  arithmetic calls on those types and printing the trial command when the
  mutator is off. It is **silent on all three suites here**, confirming the
  2026-07-21 trial from the other direction: every Big reference in main is a
  constructor, `stripTrailingZeros()`, or `BigDecimal.ZERO`, and none of those
  is an operation the mutators rewrite. Do *not* add a `declineMutator(...)`
  to memorialize that — a decline is reported stale when nothing is left for
  it to suppress, so recording one here would *add* a warning rather than
  silence one. The decline becomes the right artifact only if Big arithmetic
  lands, the scan starts firing, and a trial then measures it not worth
  enabling. Fluent calls returning their receiver are
  expressions, invisible to `VoidMethodCallMutator`: trialed
  `EXPERIMENTAL_NAKED_RECEIVER` on 2026-07-22 (`pitestMutatorTrial
  -PtrialMutators=...`) — fired 16 mutants in `iterator` (four exposed
  genuinely untested skip-branch positions in the `readXOr` defaults, killed
  by extending `TestNull`), zero in `numbers`/`util`. Enabled on `iterator`
  only. Numbers for both trials in `config/pitest/README.md`.
- **PIT minions run on the class path**, even though this repo's tasks run on
  the module path: `module-info` services are invisible to minions, and a
  test-resources `META-INF/services` is invisible to the module-path `test`
  task — a harness whose result depends on which task ran it is never
  committed. The local instance is `JsonIterParserFactory`'s `ServiceLoader`
  path, accepted as unreachable in-harness (the whitebox test setup cannot
  provide a service; see `config/pitest/README.md`). A real (main-source)
  provider would need the dual declaration — `module-info` *and*
  `META-INF/services`.
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
  (`src/test/resources/fuzz/<target>/regression-*`, replayed deterministically inside
  `check` by the plugin-generated `<Harness>SeedReplayTest` classes — provenance in
  the README beside the corpus dirs, never inside one, where a file becomes a seed)
  *and* a named regression unit test asserting the fixed behavior. A crash fixed
  without both is a crash that can return. Fuzz and minimize tasks refuse when a
  committed seed exceeds its target's `maxLen` (libFuzzer would silently truncate
  it, exploring a clip of what the seed pins — raise the cap in
  `json-iterator/build.gradle.kts` or re-minimize the seed deliberately; current
  caps have ~20× headroom over the largest seeds). Corpus dedup is
  `fuzz<Target>Minimize` — committed seeds keep their meaningful names;
  `-PadoptLocalCorpus` opts in the local `build/` corpus, and anything adopted or
  removed updates the provenance README (it folds in *every* local file — thousands
  of hash-named ones against a handful of curated seeds — so adopt for a finding
  worth keeping, not as corpus hygiene). All four targets declare a `seedCorpus`,
  so none is named by `generateFuzzReplayTests`' corpus-less advice and no
  `declineSeedCorpus(...)` is recorded here. A new target owes one or the other:
  a corpus does two independent jobs, and "a mutator reaches this format from
  scratch" answers only the bootstrap one — a target with nowhere to put a
  finding cannot satisfy the two-artifact rule above, whatever its input format
  looks like.
- **A long campaign writes to a file, and a fuzzer that stopped printing may be
  frozen, not finished.** Route `-PmaxFuzzTime=600` output straight to a file —
  never through `tee`, a pager, or a filtering pipe whose consumer can die
  mid-run. libFuzzer writes progress to that pipe, and when the reader goes away
  the next write blocks forever inside native code with the JVM parked
  `RUNNABLE` in `startLibFuzzer`, which by thread state alone is
  indistinguishable from a healthy quiet stretch. Diagnose by **CPU delta**, not
  thread state: a live fuzzing JVM accumulates CPU continuously, a frozen one
  stops cold. And a killed campaign dumps each in-flight input as a
  `crash-<hash>` artifact stamped the kill moment — dump-on-death, not findings.
  Replay them against the harness before treating any as a crash.
- **A finding that can't be fixed yet still gets its regression test — a failing
  one, committed `@Disabled`** with the finding's identifier as the reason,
  asserting the *correct* behavior so it fails while the bug lives. Never assert
  the buggy value to keep the suite green: that makes the bug look intended, and
  is exactly how findings rot. Un-ignore when the fix lands. No open findings
  today — the seven 2026-07-17 crashes are all fixed and seed-pinned.
- **`.github/workflows/fuzz.yml` exists but is `workflow_dispatch`-only** — the
  canonical weekly soak with its `schedule` block commented out, pending a
  hand-dispatched run to see what it costs. Local campaigns stay the pre-release
  ritual regardless. `fuzzWorkflowInSync` (in `check`) is now live and fails when
  a registered target is named nowhere in that file — verified empirically by
  dropping `fuzzInstant` and watching it fire. Registering a fifth target means
  adding it to the soak's task list, or naming it in a yaml comment with the
  reason it is deliberately left out; the budget step's target count (`4 *
  MAX_FUZZ_TIME`) needs the same edit.
- **Three of the four targets are saturated; don't budget hours of wall clock.**
  Measured 2026-07-24 at 600s per target: `fuzzDouble`, `fuzzNumber`, and
  `fuzzInstant` each ended with edge *and* feature counts identical to their seeded
  start (401/962, 374/831, 326/832) across 38–130M executions — more time buys
  nothing there, and new coverage needs a harness or oracle change instead. Only
  `fuzzJson` still moves, and barely (two new feature buckets, flat edge coverage).
  The 600s legs remain the pre-release ritual; treat a *longer* run as a decision
  needing a reason.
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

**`escapeJson` keeps three comparisons, not a lookup table.** The obvious
optimization — `private static final boolean[] SPECIAL` sized to `'\\' + 1`, tested
as `c < SPECIAL.length && SPECIAL[c]` — was measured on 2026-07-25 and **rejected**.
It is 2.9× faster on all-lowercase text (0x61–0x7A, every character above the guard,
which is therefore always false and perfectly predicted, and the table never read),
but **8–9% slower than the shipped code on realistic mixed-case text**, where the
guard becomes an unpredictable branch. The full picture, `clean_long` / `mixed_long`
/ `low_long` in ns/op: table 92 / 293 / 268, three comparisons 268 / 268 / 269. The
table's cost tracks the *predictability* of its guard, worst at a 41/59 split; the
comparisons are flat across every alphabet. A table would also add
static-initializer mutants that are unkillable by construction (see
`JHex$INIT_DIGITS`). Don't re-propose without a measurement on a realistic alphabet.
A branchless `c < 0x20 | c == '"' | c == '\\'` was measured too and is no better
than the short-circuit form (269.8 vs 268.5).

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

**The criterion is free cores, not load average.** A JMH run here is single-threaded and
forks run one at a time, so it needs *one* core, not an idle machine. This box has 10
(8 performance + 2 efficiency), and a load average is meaningless without that
denominator — worse, during Spotlight indexing load is inflated by I/O wait rather than
CPU (observed 2026-07-25: load 9.0 with roughly 1.6 cores actually busy, so ~8 free).
Read per-process CPU, not just `uptime`:

```sh
uptime
ps -Ao pcpu,comm -r | head -12
```

**A background daemon pegging one core does not invalidate a single-threaded
microbenchmark — measure, then judge by the error bars.** Refusing to run until the
machine is pristine costs more than it saves when eight cores are idle; the 10%-of-score
error rule already catches contamination after the fact, and it catches it *specifically*
rather than by proxy. What actually competes is a workload that wants many cores at once:
your own Gradle build, a test task, a PIT suite, a second benchmark. Keep those off; let
`spotlightknowledged.updater` have its core.

Sharpen the judgment with what the run reports rather than what the machine looked like
beforehand — an error bar over 10% of score, or the same row disagreeing across forks,
is the signal to re-run. The known distortions still stand as priors: open JetBrains IDEs
are the largest lever a human controls (one measured 50% → 8.5% CPU on close), and the
23% `bytes_reset` inflation on record came from IDEs *plus* indexing on a far busier
machine than one hot daemon.

**`spotlightknowledged.updater` does not "finish" — don't wait it out.** It starts at
boot and can sit pegged near 100% indefinitely (observed alive 11d22h *and* hot), so
treating it as a transient burst to be waited through stalls indefinitely. If it does
need clearing, `sudo killall spotlightknowledged.updater` (launchd respawns it) or
suspending indexing around the run with `sudo mdutil -a -i off` … `sudo mdutil -a -i on`
both work — but both need sudo, so they belong to the human, not the agent, and neither
is a precondition for measuring.

Send a long run's output straight to a file — never through `tee` or a filter, for the
same reason a long fuzz campaign doesn't.

**While a run is pending or measuring, make only cheap edits.** Docs, comments, and code
you will compile later are fine; a Gradle build, a test task, or a PIT suite is not — it
lands in whichever row happens to be measuring, and since contamination moves between
rows the damage is not even reproducible.

**A benchmark's input alphabet is part of its design, not a detail.** Where a
candidate's cost depends on a branch, the *distribution* of inputs decides whether
that branch predicts — and a generator written for convenience will quietly pick the
friendly distribution. `EscapeBench` originally built inputs from `'a' + rnd(26)`;
every character landed above the `c < 0x5D` guard of a candidate lookup table, so the
guard was always false, perfectly predicted, and the table never read. That reported a
2.9× win for a change that is 8–9% **slower** on realistic mixed-case text. The tell
was a third variant behaving identically to the shipped code when it should have been
faster — a result that only makes sense if the thing being measured wasn't what the
name said. When a candidate branches on character values, ranges, or lengths, add a
shape whose distribution *straddles* the branch, and one at each extreme: predictable-
true and predictable-false both flatter it, and the realistic middle is where it is
worst.

Do not invent a mechanism to explain a contaminated row. The bogus solana stream tax got
a confident, plausible explanation — a "large-object ZGC allocation path" — that was pure
fiction, and it survived precisely because it sounded like an answer. If the error bar is
over the threshold, there is nothing to explain yet.

Allocation is measurable even when the machine is noisy, and it can be the whole story:
`-prof gc` reports `gc.alloc.rate.norm` (bytes per op) to ~0.01 B/op regardless of CPU
contention. Reach for it whenever an API might be paying in GC rather than in latency —
the stream source ties on score and allocates 22× more.
