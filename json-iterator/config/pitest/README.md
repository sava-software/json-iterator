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
  bits, so beware "low bits are harmless" blanket reasoning here). Accepted
  is the provably inert residue: `head++ → head--` on the digit reads (the
  re-read hex digit is consumed as ordinary string content, invisible to a
  skip, and a duplicated low digit perturbs only bits 0–7, below the
  classification bits), `<< → >>` zeroing of the `<<4`/`<<6` terms (their
  maximum contribution cannot cross the surrogate or `0x110000` plane
  verdicts), and the outer `bc >= 0x10000` gate mutants (forced entry or
  `> 0x10000` boundary — the inner `>= 0x110000` verdict is unchanged either
  way).
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

## Untriaged debt

One item, deliberately deferred:
- `JsonIterParser` bufSize-shim NO_COVERAGE: gated behind the shim's removal
  (deprecated 2026-07-17; must ride a published release first) — meaningless
  to triage before it. Everything else in the baseline is triaged above.

Shrinking the baseline is always an improvement; growing it requires a
reason here.
