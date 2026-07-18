# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`.

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (position after a skip, exact error context, allocation
   bounds) over restating the implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason below. Acceptance is for mutants that are *equivalent with
   respect to observable behavior*, not for "hard to test".

Line numbers are part of the baseline key, so unrelated edits to a mutated
file can shift entries: the verify task then reports both stale and "new"
rows. Confirm the new rows are the shifted old ones, then refresh with
`-PupdateMutationBaseline`.

Incremental analysis (PIT history) is not wired: current PIT releases gate it
behind the commercial arcmutate history plugin. Suite scoping keeps full runs
around a minute each; revisit if that grows.

## Triaged equivalent mutants (accepted with reasons)

- `BaseJsonIterator.contextWindow` — MathMutator on the `StringBuilder`
  capacity expression (`to - from` → `to + from`): over-allocates the builder,
  output identical.
- `FieldMatcher.of` (capacity sizing) and `FieldMatcher.hash`/`match`
  boundary mutants: hash-table load factor and ascii fast-path choices —
  performance-only, results verified equal by the matcher tests.
- Allocation-shape mutants in the sized array readers (grow-always,
  trim-always) are **no longer accepted**: `TestAllocation` observes them
  through the thread-allocation counter, so they must stay killed.

## Untriaged debt

The initial baselines (seeded 2026-07-18) also carry the pre-existing
`SURVIVED`/`NO_COVERAGE` population of `BaseJsonIterator`,
`BytesJsonIterator`, and `CharsJsonIterator` from before the ratchet existed.
These are a triage backlog, not accepted equivalents: shrinking the baseline
is always an improvement, growing it requires a reason here.
