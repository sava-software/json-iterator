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

Incremental analysis (PIT history) is available via arcmutate (free licences
for open source; the build plugin activates it when `arcmutate-licence.txt`
sits at the repo root — see sava-build's `HARDENING.md`). Not adopted here
yet: suite scoping keeps full runs around a minute each. If adopted, the
pre-release gate, baseline refreshes, and convergence runs all take
`-PnoMutationHistory`.

The fuzz seed corpus replays deterministically in the unit suite
(`TestFuzzCorpusReplay`), so newly committed seeds — including promoted fuzz
findings — face PIT's mutants automatically.

## Triaged equivalent mutants (accepted with reasons)

Triaged 2026-07-18 across all three suites; grouped by the principle that
makes them equivalent. The baseline CSVs carry the exact keys.

**Slow-path / alternate-path routing** — both paths are result-identical, the
mutant only changes which one runs (performance/allocation, not behavior):
- `DoubleParser.parse` scan mutants that route more inputs to `slow()`, which
  delegates to `Double.parseDouble` — a bit-exact oracle by construction.
  Empirically confirmed over a ~1M-value deterministic corpus.
- `readInt`/`readLong` fast-vs-slow path selection and re-routing of
  already-terminated digit runs; `BytesJsonIterator.parse` SWAR-word-loop vs
  `parseTail` selection; `CharsJsonIterator.parse`/`parseFieldName` forcing
  `handleEscapes` on escape-free content (content-identical copy);
  `FieldMatcher.match` ascii-check boundary forcing the UTF-8 ground-truth
  path; `JIUtil.escapeQuotes*` prefix-fast-forward skips (the generic scan
  re-finds the same first special char).

**Arithmetic identities**:
- `InvertNegs` on the `MIN_VALUE` overflow checks: `-val == MIN` ⇔
  `val == MIN` in two's complement.
- `DoubleParser` `×10⁰ ≡ ÷10⁰`, Eisel–Lemire vs Clinger agreement at the
  2⁵³−1/2²⁴−1 boundaries, refinement/tie-range guards gated by exactness
  tests, `reduceScale(v, 0)` identity; the escape-parity `Incr` mutants in
  `JIUtil` (the counter is consumed only via `& 1`, decrement preserves
  parity).

**Unreachable defense-in-depth** — the guarded state cannot arise because an
earlier limit check already rejected it: slow-path wrapped-accumulator
`== 0` cases in `readIntSlowPath`/`readLongSlowPath`, the `scaleLong`
single-step wrap check, `FieldMatcher.hash` len == 8 word agreement,
capacity-sizing mutants in `FieldMatcher.of` (any power-of-two capacity ≥
field count matches identically).

**Static-initializer table** (`JHex$INIT_DIGITS`): built once per PIT minion
JVM before mutants activate, so table-construction mutants are unkillable by
construction. The per-call `decode` mutants all die.

**NC→SURVIVED traps** — covering the line would convert provably-equivalent
mutants on it into new SURVIVED entries: `DoubleParser` `return slow(...)`
sites whose inputs always throw inside `slow`; `parseFieldEquals` truncation
bail-outs whose slow-path true-return is structurally unreachable;
`JIUtil.escapeQuotes*` deep-escape branches whose only distinguishing inputs
strand parity-equivalent increments.

**Multibyte scan paths**:
- `containsMultiByteOrEscapePattern`: over-detection mutants only route the
  word loop to the byte-accurate slow path; the under-detection direction is
  harmless because no UTF-8 lead/continuation byte (0x80–0xF4) aliases the
  quote (0x22) or escape (0x5C) bytes the word loop acts on — corroborated
  by the 40-offset content sweeps in `TestMultiByteScanSweep`.
- Skip-path `\u` escape accumulation (`skipPastMultiByteEndQuote` bytes,
  `skipPastEndQuote` chars): the divergent `+ → −` accumulation direction is
  killed by the lone-low-surrogate skips in `TestSkip`
  (`test_skip_surrogate_escapes` — borrow propagates into the classification
  bits, so beware "low bits are harmless" blanket reasoning here). The rest
  of the residue was **sweep-verified 2026-07-21**: both variants
  reimplemented outside the codebase and diffed on observable outcome
  (return position + exception identity, including `reportError`'s embedded
  offset; the position-less "invalid surrogate" and `JHex` messages compare
  on identity alone). Domains: all 65,536 escape values × both surrogate
  states × every truncation point × an invalid digit in every position (both
  sources), and the full 4-byte domain lead `F0`–`F7` × 256³ continuation
  bytes. The killed `<<12`/`<<18` mutants were swept as controls and
  diverge, cross-validating the model.
  - **Verified equivalent, accepted**: `head++ → head--` on the digit-4 read
    (all divergence is post-throw cursor state behind a position-less
    exception); `<<4 → >>4` zeroing (bits 4–7 sit below every
    classification bit); `<<6 → >>6` zeroing and both outer `bc >= 0x10000`
    gate mutants (forced entry and `>` boundary — bits 0–11 and the 0x10000
    corner sit below every plane verdict).
  - **Falsified and killed**: `head++ → head--` on the digit-3 read had been
    accepted as "invisible to a skip"; the sweep found 65,536 observable
    divergences — on documents truncated mid-escape the lagging cursor
    misses the tail check, completes the escape from re-read digits, and
    reports "invalid surrogate" where the real code reports
    "incomplete string, offset: N". Killed on both sources by
    `TestSkip.test_skip_truncated_escape_reports_cut_offset`.
- `parseMultiByteString` grow-check always-grow mutants: allocation-only,
  same family as the sized-array-reader equivalents `TestAllocation` kills —
  the never-grow directions are killed, only always-grow is accepted.

**ASCII word-loop tail handling** (`skipPastEndQuote`, `parseString`,
`parseBase64String` in `BytesJsonIterator`): the divergent directions are
killed by the length sweeps in `TestString`/`TestSkip`
(`*_at_buffer_tail_across_lengths`: forced word-loop entry reads past an
exact-sized buffer; disabled or forced re-align corrupts the post-skip
position, throws on valid input, or spins on the final window until PIT's
timeout). The accepted remainder is equivalent by construction:
- entry `head + 8 > tail` mutants ("true"/boundary): route to the
  byte-accurate scalar/escaped slow path — result-identical, routing only
  (same family as the slow-path routing group above), likewise the forced
  multibyte/escape-detection mutants and `skipPastSingleByteEndQuote`'s
  escape-check mutants.
- re-align `nextOffset > tail` boundary (`>=`): fires one window early, but
  `tail - 8` then equals the offset the cursor already holds — identical.
- final-window `i < tail` boundary (`<=`): at the `i == tail` corner
  (unterminated input, 8-aligned) the mutant re-scans the final
  already-scanned window once, then throws the same incomplete-string error.
- `decodeBase64` trim branch (`limit == length` both directions): the JDK's
  strict decoder sizes its output exactly for every valid input that reaches
  it (invalid input throws first), so the copy is defensive; forcing it is
  allocation-only.

## Mutator-set trial (2026-07-21)

Per HARDENING.md ("the mutator set bounds what the ratchet can see"),
`EXPERIMENTAL_BIG_INTEGER` was trialed on every suite: `iterator` 1904 → 1904,
`numbers` 326 → 326, `util` 335 → 335 generated mutants — **zero fires**. The
`readBigDecimal`/`readBigInteger` paths construct their results from parsed
chars; no `add`/`multiply`-family arithmetic exists in mutated classes for the
mutator to rewrite. Left off (enabling a mutator that cannot fire is baseline
churn for nothing); re-trial if Big arithmetic is ever introduced.

## Convergence check (2026-07-21)

Per HARDENING.md's convergence method: two solo passes per suite and two
`qualityGate` passes, report directories deleted between runs, diffed on
per-mutant status keyed `(class, method, line, mutator)` — **zero differences**
in all nine comparisons (solo-vs-solo, gate-vs-gate, solo-vs-gate, per suite).
The stale-acceptance sweep (each baseline row against the union of unkilled
sets across all four runs) matched **every** row in at least one mode — no row
is widening the gate for nothing. The `TIMED_OUT` rows (7 iterator, 2 util)
were stable in both modes, so the baselines carry no flip-insurance rows to
revisit. The abstract-base `@Execution`/`@TestInstance` instability cannot
apply here: the test suite has no abstract test classes and uses neither
annotation.

**ServiceLoader factory path — unreachable in-harness**
(`JsonIterParserFactory`, 5 NO_COVERAGE): the load-success path needs a
registered provider, and the whitebox test setup patches tests *into* the
main module — a provider would need a `provides` directive, which cannot come
from patched-in test sources (the JVM has no `--add-provides`) and does not
belong in the production `module-info`. What would reach it: a blackbox test
suite with its own module descriptor providing a test factory. Covering only
the failure path was considered and rejected — it converts the `loadParser`
return-value mutants into NC→SURVIVED traps (the call throws before either
`return` completes) without observing the load behavior. Accepted as
unreachable in-harness, not as equivalent.

The baseline is otherwise fully triaged; no untriaged debt remains
(the `JsonIterParser` bufSize-shim family closed 2026-07-21 with the shim's
removal — 25.3.0 carried the `forRemoval` marker — and `TestJsonIterParser`
covering the surviving convenience overloads).

Shrinking the baseline is always an improvement; growing it requires a
reason here.
