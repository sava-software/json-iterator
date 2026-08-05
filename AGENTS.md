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

The general process is sava-build's, and the installed plugin is its authority:
`./gradlew :json-iterator:hardeningHelp` lists the task and property surface the
resolved version actually has, and `:json-iterator:hardeningAgentTemplate`
prints the operator rules it carries. **Do not restate either here** — a copy
goes stale at the next bump and the plugin has no way to tell you. What belongs
in this file is what
the plugin cannot know: which suite owns which class, what has been measured,
why a mutant was accepted, and what a green gate still does not prove — the
last of which is inventoried in `HARDENING_NOTES.md`.

<!-- The block below is this repo's adaptation of the agent-instructions
     template in sava-build's HARDENING.md. `agentsTemplateInSync` (in `check`)
     fails until the marker acknowledges the resolved plugin's digest; re-diff
     the block against `hardeningAgentTemplate` before moving it, because a
     changed bullet can require code rather than prose. Acknowledged together
     with both plugin pins for sava-build 21.5.22. -->
<!-- hardening-template sha256:d128cb8208fa -->

- **Suite choice is reachability, not habit.** `pitestNumbers` mutates
  `DoubleParser`; `pitestUtil` mutates `JHex`/`JIUtil`/`InstantParser`/
  `FieldMatcher`; `pitestIterator` mutates everything else. The first two finish
  in ~10s, `pitestIterator` costs ~90s. Test-only edits still owe the suite whose
  mutants those tests kill; doc, build-script and comment changes owe no suite.
- **Certification is owned by the local release checklist, not CI.** CI
  deliberately runs only `check` (sava-build's shared workflow) — the serialized
  PIT suites are too slow for hosted runners. Don't wire the full gate into CI to
  "fix" that; it's a decision. The pre-release ritual is certification plus an
  explicit local `fuzzAll -PmaxFuzzTime=600 -PmaxParallelFuzzTargets=1`
  campaign (§ fuzzing) plus a
  change-scoped jmh A/B per benchmark discipline.
- **These baselines' rows predate label support** (notes 21.5.10, refresh seeding
  21.5.12) and print as `unlabeled` — recorded state, not new debt. Label a row
  with its `config/pitest/README.md` family when you touch it, never by bulk
  inference: a misassigned label reads as finished triage. When a claimed
  equivalence spans a sweepable domain, verify it empirically — reimplement both
  variants, diff them over the range, and record the range in the note. A sweep
  survives refactors that rot a prose argument.
- **Nothing here memoizes**, so the cache-miss fixture trap does not currently
  apply: `JHex$INIT_DIGITS` is a static table with its own family argument, not a
  cache. This binds any future one.
- **Baseline provenance.** These files moved to line-less keys on 2026-08-01
  (parse/re-render, no mutation run, every row identity preserved — counts have
  moved since, so read them from the CSVs). That retired the whole drift
  apparatus, and with it the 2026-07-26 mispaired-shift incident, which is now
  dead by construction rather than by classifier. Expect line-drift advisories on
  runs after a record rewrite: legacy line fields were allowed to lag, and
  promoting them to tags surfaces the lag as re-read prompts, not regressions.
- **Mutation provenance here is fully bound as of 2026-08-04.** All three
  suites carry the paired record — `<suite>-pitest-version` at PIT 1.25.9 and
  `<suite>-pitest-toolchain.tsv` — adopted through `pitest<Suite>BaselineRebase`,
  which retained every accepted row and added none: 1.25.9 generates exactly the
  populations 1.25.8 did (1919/326/394). That closed two older states: `util` was
  legacy-unversioned, and `iterator`/`numbers` were *torn* (a version stamp with
  no sidecar), which the plugin now fails closed on. The sidecars record ArcMutate
  as `absent` on all three — the machine-checked form of the namespace decision in
  `HARDENING_NOTES.md`, so a certificate appearing at this repo's root would now
  be an announced toolchain transition rather than silent population churn. Never
  hand-edit either file; the rebase task writes them.
- **Identical rows are siblings, never hand-dedupe.** These baselines carry such
  rows at `matchPattern`, the `DoubleParser` scan guards, and `INIT_DIGITS`. The
  2026-07-23 multiset re-triage is the proof it pays: two "siblings of accepted
  equivalents" were real lenient-literal-skip gaps, killed in `TestSkip` along
  with eight neighbouring rows the exact-offset assertions swept up.
- **Three keys here hold an accepted survivor and an audited timeout at once** —
  `CharsJsonIterator.skipPastEndQuote/MathMutator`,
  `JIUtil.escapeQuotesChecked/IncrementsMutator`, `FieldMatcher.of/MathMutator`.
  Under sava-build 21.5.20 they reported a flip on every run in byte-identical
  populations (casebook: "The flip that fired forever"); the per-key count
  comparison that landed in 21.5.21 silenced them, so a warning at any of them
  is real again.
- **The audited timeout set here** is 7 `iterator` rows and 2 `util`; `numbers`
  has never timed out, so it has no file and the check is inert for that suite.
  Every member carries a `# line` anchor as of 2026-08-03 — before that the rows
  were bare and the line-drift check was silently inert. Causes live in
  `config/pitest/README.md` §"Timed-out mutants (audited set)"; admit a newcomer
  only with its cause written, and prefer removing the cause (a call budget or
  bound in the harness) over leaning on the timeout. Two members currently sit on
  the quiet counter and are deliberately not retired — the README records why and
  what would justify retiring them.
- **Randomized tests use fixed seeds, and never sleep** (`TestDouble`,
  `TestString`): PIT re-runs the covering tests once per mutant, so a single real
  wait costs minutes across a suite. Per-run exploration is the fuzz targets' job
  — don't reintroduce `new Random().nextLong()` seeding.
- **Three template rules have no subject in this repo yet, and bind any future
  one:** nothing reads a clock (`InstantParser` parses input, it never asks for
  *now*), there are no stubs or fakes, and no collection-returning API routes
  through an unmodifiable copy.
- **Kill rates are bounded by the mutator set.** `BigInteger`/`BigDecimal`
  arithmetic is method calls, invisible to `STRONGER`'s arithmetic mutators.
  Trialed `EXPERIMENTAL_BIG_INTEGER` on 2026-07-21 across all three suites: zero
  fires (1904/326/335 generated, unchanged) — the readers construct Big values
  from parsed chars but do no arithmetic on them. Left off; re-trial if Big
  arithmetic is introduced. Each run re-checks that from the other direction by
  scanning the recompiled constant pools, and is **silent on all three suites**:
  every Big reference in main is a constructor, `stripTrailingZeros()`, or
  `BigDecimal.ZERO`. Do *not* add a `declineMutator(...)` to memorialize that — a
  decline is reported stale when nothing is left to suppress, so it would add a
  warning rather than silence one. It becomes the right artifact only if Big
  arithmetic lands, the scan fires, and a trial then measures it not worth
  enabling. Fluent calls returning their receiver are expressions, invisible to
  `VoidMethodCallMutator`: trialed `EXPERIMENTAL_NAKED_RECEIVER` on 2026-07-22 —
  fired 16 mutants in `iterator` (four exposed genuinely untested skip-branch
  positions in the `readXOr` defaults, killed by extending `TestNull`), zero in
  `numbers`/`util`. Enabled on `iterator` only. Numbers for both trials in
  `config/pitest/README.md`.
- **PIT minions run on the class path**, even though this repo's tasks run on the
  module path. `TestParserFactoryLoading` is the local instance of the
  probe-and-branch pattern: its test-resources `META-INF/services` registers
  fixture factories the class-path minions see (killing the whole `loadParser`
  pipeline) while the module-path `test` task asserts the no-provider error —
  which is how the former "unreachable in-harness" acceptance for that family was
  disproven on 2026-08-02. Its fixtures are nested inside the `Test*` class
  deliberately; top-level fixtures would join the mutated population. A real
  (main-source) provider would still need the dual declaration — `module-info`
  *and* `META-INF/services`.
- **Ten baseline rows sit in blocks that always exit by throw** (the
  Throw-terminated blocks family in `config/pitest/README.md`), established here
  on 2026-08-02: `TestDouble` had fed quoted `"1e"` through `readDouble()` since
  the parser landed and `DoubleParser.parse:136` read `NO_COVERAGE` in every
  report regardless. Before writing a test purely to flip a `NO_COVERAGE` row,
  check whether its block can complete. What such a row is owed is a test
  asserting the throw's contract, not coverage.
- **"No untriaged debt" is a claim about labels, not arguments.** On 2026-08-01
  seven `NO_COVERAGE` rows in `readNumberOrNumberString`, `skipUntil` and
  `closeObj` turned out to have no argument anywhere in `config/pitest/README.md`
  — they were `unlabeled`, which the label-mention warning exempts, so nothing
  had ever flagged them. They were untested branches on covered public API, not
  equivalents, and were killed rather than argued. **Audit an unlabeled row by
  looking for its argument, not by trusting its label state**: grep the README
  for the class-and-method before believing a row is settled.
- **A mutant is a question, not a specification** (shared rule, adopted with
  sava-build 21.5.23; it governs triage from here on and demands no rewrite of
  past work). Before writing a killing test, state the property the code is
  *externally* meant to hold and an oracle independent of the current
  implementation — the public contract, a reference implementation, a caller
  invariant, a domain rule. If the oracle contradicts what the code does today,
  that is a production bug: prove it with a regression test that fails against
  the *unmutated* code, then fix the source. Never add a passing assertion that
  merely pins current behaviour, which promotes a bug to a specification.
  Report nontrivial behavioural clusters — not individual mutants — as
  `Property: … | Oracle: … | Outcome: missing assertion / production bug /
  accepted equivalent`. This repo has already paid for the rule twice, which is
  why it is worth keeping rather than importing: the 2026-08-02 contract tests
  killed 10 accepted `SURVIVED` siblings whose routing arguments had only ever
  been checked in the equivalent direction, and the 2026-08-01 pass found seven
  `NO_COVERAGE` rows that were untested public API rather than the equivalents
  their label state implied. Both were oracle failures, not assertion failures.
- **Allocation bounds are asserted where allocation is the contract — and only
  there.** `TestAllocation` pins the zero/exact-allocation contracts via
  `ThreadMXBean` counters; new API whose *stated design goal* is allocation
  behaviour gets the same treatment, and that is also how otherwise-"equivalent"
  grow/trim mutants get killed. But the harness is a last resort: it re-runs once
  per mutant, every measured result must go to a `static volatile` sink so escape
  analysis can't delete the allocation under test, and a thin margin (observed:
  mutant at 88 bytes vs a 90-byte bound) is a flaky harness with extra steps. An
  incidental micro-optimization that only an allocation bound could observe is a
  mutant to accept, with "allocation routing only" as the written reason.
- **All test-source classes here are `Test*` or `*Fuzz*` today**, and the
  `iterator` suite excludes those patterns — but a shared fake extracted to a
  top-level `RecordingFoo`/`StubFoo` would match neither and silently join the
  mutated population. After registering or widening a suite, confirm no mutated
  class lives under `src/test`.
- **Ownership here reads `35 production class(es) owned, 1 explicitly
  declined`.** `PowersOfFive` is the single declined opt-out — it sits inside the
  `iterator` target universe, is excluded there, and carries
  `declineExclusionAudit(glob, reason)` naming what owns its correctness instead
  (see `HARDENING_NOTES.md`). `HARDENING_NOTES.md` is supplemental context and
  waives nothing.
- **No ArcMutate acceleration applies here.** The Sava OSS certificate is scoped
  to `software.sava.*` packages and this library's are
  `systems.comodal.jsoniter.*`, so every run is open-source PIT from scratch —
  see `HARDENING_NOTES.md` §"No ArcMutate acceleration here" before copying a
  certificate in. A suite that got faster without getting narrower is therefore
  always a bug report here; there is no `[history]` exception to appeal to.
- **Witnessed 2026-08-03: a transient `RUN_ERROR` fails a whole certification.**
  A cold certification died in `pitestIteratorVerify` on `RUN_ERROR x1`, and the
  identical re-run certified all three suites — one occurrence in three
  certification runs, so load. Budget for it: a release certification can lose
  two minutes to a single flaky minion, and the correct response is to run it
  again, never to reach for a refresh flag. **Copy the offending coordinate out
  before re-running** — the quiet re-run overwrites the only report that held it,
  and without the coordinate the same-mutant-twice test cannot be applied to the
  next occurrence.
- **Verify by the absence of failures, not the presence of passes.** A green
  `check` can mean the build cache short-circuited rather than that tests ran —
  on 2026-08-03 a post-`clean` `check` came back in 698ms with `:test`
  `FROM-CACHE` and zero `PASSED` lines. Confirm the task actually executed before
  reporting a run as evidence.

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
- **The pre-release campaign is local and explicit, not scheduled.** Run
  `./gradlew --continue :json-iterator:fuzzAll -PmaxFuzzTime=600
  -PmaxParallelFuzzTargets=1`; `fuzzAll` derives its dependencies from the four
  registrations, so it cannot drift from a hand-written task list the way a
  workflow can, and it writes
  `.pitest-history/local-fuzz.tsv` after every selected target succeeds (schema
  4 as of sava-build 21.5.23, carrying the plugin JAR hash, the budget, the
  parallel width and per-target execution counts). That path is deliberately
  outside `build/`, so unlike the certification receipt it survives `clean` — it
  is still git-ignored machine-local state, so it is durable against the build,
  not against the machine. A campaign whose seconds and outcome nobody retained
  is a recollection, not a record, and it becomes evidence one of two ways:
  - **Driven by sava-build's `tools/local-fuzz.sh --release --seconds <N>`**
    (the fleet path): the runner finds this repo's `local-fuzz.tsv`, hashes it,
    and copies it into an immutable SHA-bound run bundle under *sava-build's*
    `build/hardening/local-fuzz-runs/run.*/aggregates/`, with the canonical
    receipt an atomic pointer at that bundle. The **sava-build release owner**
    retains that run directory with the release record — outside the Git tree
    it certifies — and `--verify-receipt` rehashes it without rerunning Gradle.
    Nothing here is retained, and nothing needs to be.
  - **Run standalone from this repo**: nothing copies it anywhere. The fuzz
    receipt at least survives `clean`; `build/hardening/pitest-certification.tsv`
    does not, so whoever certifies copies that one into the release record
    before the next `clean` or the evidence is gone.

  `--continue` lets independent
  targets finish after one finds a crash; Gradle still exits non-zero.
  **There is no GitHub fuzz workflow** — `.github/workflows/fuzz.yml` was
  deleted on 2026-08-03. Campaigns are locally owned and run on purpose; no
  schedule, no dispatch, nothing to keep a task list in sync with.
- **Three of the four targets are effectively saturated; don't budget hours of
  wall clock.** Re-measured 2026-08-05 at 600s per target, width 1, 314M
  executions total: `fuzzInstant` 326/832 and `fuzzNumber` 374/831 are
  edge- and feature-identical to the 2026-07-24 figures, so those two are flat.
  `fuzzDouble` moved from 401/962 to **403/963** across 104M executions — a
  trickle, not a plateau, so "saturated" is an approximation there rather than a
  fact. `fuzzJson` is the one that still explores and is also the slowest
  (29.6k exec/s against 85k–234k; cov 472, ft 2864). Budget accordingly: more
  time buys almost nothing on instant and number, and new coverage there needs a
  harness or oracle change rather than a longer run.
  **Width is deliberately 1.** Those per-target figures were measured with one
  target on the machine at a time, so a campaign that widens `fuzzAll` is not
  comparable against them — libFuzzer's exec rate, and therefore its coverage
  curve, is sensitive to contention. Widen only with a fresh baseline measured
  at the same width.
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
