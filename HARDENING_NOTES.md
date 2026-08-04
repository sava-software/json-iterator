# What the ratchet cannot see here

This repo's instance of the inventory sava-build's `HARDENING.md` asks every
consuming repo to keep. The generic edges live there; this file names the
*local* ones, so that a green `hardeningCertify` — every suite freshly
observed and clean against `json-iterator/config/pitest/` — is read as exactly
what it proves and no more. The process contract itself is in `AGENTS.md`
§"Verification & the mutation ratchet"; this is the bound on it.

## The mutator set

`STRONGER` on all three suites, plus `EXPERIMENTAL_NAKED_RECEIVER` on
`iterator` only (trialed 2026-07-22: 16 mutants, four of them genuinely
untested skip-branch positions in the `readXOr` defaults; zero fires in
`numbers`/`util`, so it is not enabled there and dropped fluent calls in
those two suites are invisible).

`EXPERIMENTAL_BIG_INTEGER` is **off**, trialed 2026-07-21 across all three
suites with zero fires (1904/326/335 generated, unchanged). The library
constructs `BigInteger`/`BigDecimal` from parsed characters and never does
arithmetic on them, so there is nothing for those mutators to rewrite. Each
`pitest<Suite>` run re-checks that from the other direction by scanning the
recompiled constant pools for arithmetic calls on those types; it is silent
on all three suites today. Deliberately **no** `declineMutator` record — a
decline is reported stale when nothing is left for it to suppress, so
recording one here would add a warning rather than silence one. Numbers for
both trials are in `json-iterator/config/pitest/README.md`.

## The class path is PIT's world

The tasks run on the module path; PIT minions run on the class path. A
`module-info` `provides` clause is invisible to a minion, and a
test-resources `META-INF/services` is invisible to the module-path `test`
task — so no *single* set of assertions holds in both worlds, but a harness
that probes `ServiceLoader` and branches its assertions passes
deterministically in each. `TestParserFactoryLoading` is the local instance:
class-path minions see its test-resources providers and kill the whole
`loadParser` pipeline; the module-path `test` task asserts the no-provider
error. (This family spent three weeks accepted as "unreachable in-harness"
before that harness was tried — the missing capability named in the
acceptance was larger than what the kill needed.) A real main-source
provider would still need the dual declaration — `module-info` *and*
`META-INF/services`.

## PIT block coverage cannot see throw-terminated blocks

Coverage probes sit at block *ends*, so a `return f(...)` whose call throws
for every input reaching it never registers — the line reads `NO_COVERAGE`
under any test, and its return-value mutants are permanently unexercisable.
Ten baseline rows are this shape (`DoubleParser.parse` sign/exponent
bail-outs, `parseFieldEquals` truncation bail-outs), each executed by a test
asserting the throw's contract. Consequences: a `NO_COVERAGE` row is not
proof no test reaches the line, and the NC→SURVIVED "trap" concern is void
for these blocks — the mutants cannot become `SURVIVED`, because that would
require the block to complete.

## Kills come only from `targetTests`

Every suite targets `systems.comodal.jsoniter.Test*` plus
`systems.comodal.jsoniter.*SeedReplayTest`. Two consequences:

- **The JMH benchmarks kill nothing.** They live in the `jmh` composite build
  (`jmh/`), which is neither mutated nor a source of kills. Code exercised
  only by a benchmark reads as `NO_COVERAGE` no matter how hard the benchmark
  drives it.
- **Test helpers are not mutated but are not covered either.** The
  `systems.comodal.jsoniter.factories.*` test package (plural — distinct from
  the mutated main package `systems.comodal.jsoniter.factory.*`, singular)
  supplies the parameterized byte-array / char-array / stream sources. A bug
  in a factory shows up as every parameterization silently exercising the
  same path, which no mutant can report.

Fuzzing is deliberately explicit and local: `fuzzAll` derives all four
registered targets from the registrations, so it cannot drift from a
hand-written task list. Its receipt lands at `build/hardening/local-fuzz.tsv`,
which is git-ignored and destroyed by `clean` — durability is somebody's job,
not the file's. Under sava-build's `tools/local-fuzz.sh --release` that job is
done for us: the runner hashes this repo's receipt into an immutable run bundle
under sava-build's `build/hardening/local-fuzz-runs/`, retained by the
sava-build release owner outside the tree it certifies. A campaign run
standalone from here is retained by nobody unless whoever ran it copies the
receipt out of `build/` first — the same caveat applies to
`build/hardening/pitest-certification.tsv`. The recorded release budget is
`-PmaxFuzzTime=600` per target; the saturation measurement behind that number
is in `AGENTS.md`, and three of the four targets buy no new coverage past it.
There is no GitHub fuzz workflow: `.github/workflows/fuzz.yml` was deleted on
2026-08-03, so a campaign happens only when someone runs one. Between
campaigns, `check`'s seed replay guards inputs already found — never the code
that changed since.

The fuzz targets contribute in one direction only: each registered target's
class is auto-excluded from every suite's mutant population, while its
generated `<Harness>SeedReplayTest` joins `targetTests` as a killer. So
landing a new seed can flip `NO_COVERAGE` rows to `SURVIVED` — behavior that
was never observed, now observed and undetected. Triage those; don't refresh
them away.

## Excluded classes

Two categories, and only the second is a hole:

- **Partition handoffs.** `iterator` excludes `DoubleParser` (owned by
  `numbers`) and `JHex`/`JIUtil`/`InstantParser`/`FieldMatcher` (owned by
  `util`). Every one of those is mutated by a sibling suite, so the exclusion
  audit subtracts them and they are covered, not skipped.
- **One declined opt-out: `PowersOfFive`.** No suite targets it. Constant
  power-of-five tables produce slow, low-value mutants, and a wrong table
  entry surfaces as a killed `DoubleParser` mutant in `numbers` — recorded
  with `declineExclusionAudit` in `json-iterator/build.gradle.kts` so the
  decision keeps earning itself rather than reading as a forgotten glob.

There are no temporary package gaps: `iterator`'s `systems.comodal.jsoniter.*`
catch-all spans package separators, so the `…jsoniter.factory` sub-package is
mutated rather than skipped, and `PowersOfFive` is the only class no suite
owns.

The audit has three surfaces and only the third can fail a build. It runs
inside each real `pitest<Suite>` execution and, statically, in
`pitest<Suite>Debt` whenever a prior run left recompiled classes behind — both
advisory. Whole-population `mutationOwnershipAudit` **fails**: it enumerates
every compiled production class and throws on an unowned class, a reason-less
decline, or a decline gone stale. It is a mandatory leg of `hardeningCertify`
and reachable from neither `check` nor `qualityGate`, so run it directly when
changing a glob; it reads `35 production class(es) owned, 1 explicitly
declined` today. These notes do not waive it — the `PowersOfFive`
`declineExclusionAudit` is what keeps the population complete, not merely what
silences a warning. Widening a glob without reading its output is how this
list goes stale.

## No ArcMutate acceleration here

The Sava OSS certificate committed at sava-build's root is scoped by Java
package (`packages=software.sava.*`). Every class this repo mutates is
`systems.comodal.jsoniter.*`, so the package leg of the eligibility test
fails — the shared `software.sava` Maven group is not the scope field, and
package scope alone would not be enough anyway. Do not copy
`arcmutate-licence.txt` here: activation is presence-based, with no scope
check in the plugin, so nothing but this paragraph stands between an
accidental copy and an unlicensed run. Every suite runs on open-source PIT and
re-earns each status from scratch — which is what `hardeningCertify` forces
even where history *is* available. `.pitest-history/` stays in `.gitignore`
regardless: the verify's run-to-run status stash lives there.

## The same-key swap

Baselines are keyed line-lessly (`class,method,mutator,STATUS`), so killing
one mutant and introducing another at the same key in one change leaves the
multiset unchanged and the new mutant inherits the old acceptance. The only
trace is the line-drift advisory. This is a deliberate trade, documented in
`AGENTS.md`; it is listed here because it is a thing a clean run does not
rule out.

## Nothing here is generated or reflective

No annotation processors, no generated sources, no reflective dispatch in
main beyond the `ServiceLoader` path above. If that changes, the new code
carries its correctness on its own tests, not on this ratchet — and this
section is where that gets written down.
