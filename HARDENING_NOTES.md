# What the ratchet cannot see here

This repo's instance of the inventory sava-build's `HARDENING.md` asks every
consuming repo to keep. The generic edges live there; this file names the
*local* ones, so that a green `qualityGate` — every suite clean against
`json-iterator/config/pitest/` — is read as exactly what it proves and no
more. The process contract itself is in `AGENTS.md` §"Verification & the
mutation ratchet"; this is the bound on it.

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
task, so no harness can satisfy both. The local instance is
`JsonIterParserFactory`'s `ServiceLoader` path, accepted as **unreachable
in-harness** (not "equivalent"), with its argument in
`json-iterator/config/pitest/README.md`. A real main-source provider would
need the dual declaration — `module-info` *and* `META-INF/services`.

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

Fuzzing is **not on a schedule yet**: `.github/workflows/fuzz.yml` is the
canonical weekly soak with its `schedule` block commented out, dispatch-only
until a hand-run shows the cost. So between pre-release rituals, the four
targets explore nothing new and `check`'s seed replay guards only inputs
already found — never the code that changed since.

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

The exclusion audit runs inside each real `pitest<Suite>` execution and,
statically, in `pitest<Suite>Debt` whenever a prior run left recompiled
classes behind. Widening a glob without reading its output is how this list
goes stale.

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
