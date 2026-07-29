# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,line,mutator,status`, optionally followed by a
`# <label>` note naming the row's acceptance family.

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Prefer asserting the property the
   mutant breaks (position after a skip, exact error context, allocation
   bounds) over restating the implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run with `-PupdateMutationBaseline` and record
   the reason below. Acceptance is for mutants that are *equivalent with
   respect to observable behavior*, not for "hard to test". The refresh seeds
   each new row `# untriaged`; finishing triage means replacing that with a
   short family label whose argument lives in a section below (mentioned there
   as `# <label>` — the verify and debt listing warn on labels with no such
   mention, so a typo can't open a phantom family). Rows accepted before label
   seeding arrived (sava-build 21.5.10) carry no label and print as
   `unlabeled` in the verify's per-label counts — their arguments are complete
   below; label a row with its family when touching it rather than by bulk
   inference.

Line numbers are part of the baseline key, so unrelated edits to a mutated
file can shift entries: the verify classifies each pairing (`shifted` /
`newly covered` / unexplained) and pure line drift passes on its own with a
notice — see sava-build's `HARDENING.md`. The baseline is a **multiset**:
identical rows are sibling mutants of one compound condition (one per
operand or branch direction), so duplicate lines are legal and must never be
hand-deduped.

Incremental analysis (PIT history) is available via arcmutate (free licences
for open source; the build plugin activates it when `arcmutate-licence.txt`
sits at the repo root — see sava-build's `HARDENING.md`). Not adopted here
yet: suite scoping keeps full runs around a minute each. If adopted, the
pre-release gate, baseline refreshes, and convergence runs all take
`-PnoMutationHistory`.

The fuzz seed corpora replay deterministically in the unit suite via the
plugin-generated `<Harness>SeedReplayTest` classes (one per fuzz target), so
newly committed seeds — including promoted fuzz findings — face PIT's mutants
automatically. The suites' `targetTests` include `*SeedReplayTest` so the
replays participate as killers.

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
  `JIUtil`, label `# escape parity`: every `escapes` counter is consumed only
  via `(escapes & 1) == 0`, and flipping the increment direction maps a run
  of n to −n (scan loops) or 2−n (checked backward counts) — same parity
  either way, so the direction is unobservable.

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
bail-outs whose slow-path true-return is structurally unreachable. The
`JIUtil.escapeQuotes*` deep-escape branches were deliberately taken out of
this set on 2026-07-26: the backslash-run tests
(`testEscapeQuotes*BackslashRunBeforeQuote`, `...LeadingNewlineOnly`) killed
20 accepted mutants — 11 of the 13 NO_COVERAGE entries plus 9 SURVIVED rows
(util 331→351 of 394 detected) — at the cost of the two remaining
NO_COVERAGE mutants surfacing as stranded `# escape parity` increments.
Public-API branches with no test were worth more than the trap avoided.

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
- `escapeJson` / `ensureCapacity` buffer sizing.
  Label: `# allocation routing`, triaged 2026-07-25 with the `char[]` rewrite.
  Four rows, all
  producing byte-identical output because every shortfall is corrected by
  `ensureCapacity` before anything is written:
  - `escapeJson:68` `new char[len + 8 + (len >> 3)]`, two MathMutator siblings.
    Only the *initial* capacity moves; a smaller one just grows sooner and a
    larger one wastes a little. The third sibling (`len - 8`) is killed — it
    goes negative on short input and throws.
  - `ensureCapacity:120` `needed <= out.length` → `<`: at exact equality the
    mutant grows a buffer that already fits. Same array contents.
  - `ensureCapacity:123` `out.length << 1` → `>> 1`: `Math.max(needed, half)`
    is then always `needed`, so the buffer is sized exactly instead of doubled —
    correct output, just more frequent growth.
  Deliberately not chased with `TestAllocation`: per AGENTS.md the allocation
  harness is a last resort, and these are precisely the "incidental
  micro-optimization only an allocation bound could observe" case it names as
  accept-worthy. The *observable* directions here are killed —
  `TestJIUtil.testEscapeJsonGrowsPastInitialCapacity` kills both under-request
  mutants on line 77 (`n + span - 6` runs off the end of a 64-control-character
  buffer; `n - span + 6` off a 15-char one where a 13-char span meets the first
  growth point).
- `matchPattern` (the Hacker's Delight zero-byte finder): the three surviving
  MathMutator siblings (`|→&` twice, dropped `~`) each flag a strict superset
  of lanes — verified per-lane over all 256 byte values (no cross-lane
  carries: `(x & 0x7F…) + 0x7F…` cannot carry out of a lane, in the real
  expression or any survivor). The function is shared by the escape/multibyte
  guard *and* the quote matcher, and every word loop checks the guard first,
  so an over-detecting mutant trips
  `containsMultiByteOrEscapePattern` on every word and routes the entire scan
  to the byte-accurate scalar tails before the corrupted quote match can
  fire — result-identical, routing only. Confirmed live (2026-07-23): with
  the `|→&` mutant compiled in, `matchPattern` returns all-lanes-flagged for
  plain ASCII yet the full unit suite and long-ASCII `readString` probes
  produce identical results. The under-detecting siblings (`&→|`, `+→−`)
  corrupt and are killed. Any refactor that gives quote matching its own
  pattern function or reorders the guard voids this argument.

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

## Timed-out mutants (audited set)

`TIMED_OUT` is detected — these mutants never enter a baseline — but the
watchdog observed slowness, not wrongness: for exactly these mutants the
ratchet cannot see a weakened covering assertion, because a timeout keeps
"detecting" no matter what the test asserts. Per HARDENING.md, the summary's
`N timed out (load-dependent)` is therefore an audited set, not a count: every
member is listed here with the structural cause that makes it spin, and a
mutant timing out that is *not* on this list is something a reviewer stops on.
Members flip `KILLED`↔`TIMED_OUT` run to run — the covering test reaching a
failure races the watchdog over a dead mutant, benign in both directions — so
per-run counts sit at or below the set size. (The 2026-07-21 convergence check
recorded 7 iterator members; one has since settled to `KILLED`.) Membership is
machine-checked: `iterator-timeouts.csv` / `util-timeouts.csv` hold the
`class,method,mutator` keys, and the verify warns on any timeout outside them
(as well as on malformed rows, on a member no mutant matches, on one whose
class and method appear nowhere together below, and — via a machine-local
quiet counter in `.pitest-history/` — on a member with no timeout in 3+
consecutive mutation runs). All of it is advisory by default, re-printed in
the end-of-build summary; `-PstrictTimeoutAudit` escalates the
unaudited-newcomer/malformed/no-set findings to failures on certifying runs,
and the static checks (row shape, cause presence) also run in
`pitest<Suite>Debt`. `numbers` has never timed out, so it has no file and the
check is inert for that suite. The keys are deliberately line-less — drift cannot
churn membership — which is also the check's resolution: a *new* timed-out
mutant inside an already-audited method+mutator matches the existing member
silently. That is why each cause below names the line it argues about; re-read
those lines whenever the code at them changes, because a clean run certifies
"no new method+mutator", not "no new mutant".

As of 2026-07-26 — 8 members, 6 iterator + 2 util, numbers none:

**iterator**
- `BaseJsonIterator.reduceScale:1021`, two mutants (`scale--` → `scale++`;
  loop condition → `true`): the counter crossing the negative `scaleLimit` is
  the loop's only exit; both remove it and the divide loop spins on a settled
  quotient of 0.
- `BaseJsonIterator.skipObject:1122` (scan cursor `i++` → `i--`): the
  `i == tail` bound is an equality a backward walk never meets, and the
  string-skip re-entry (`i = head - 1` after `skipPastEndQuote()`) can pull
  the cursor back into the same cycle indefinitely.
- `BytesJsonIterator.parseMultiByteString:575` (escape-decode `buf[head++]` →
  `buf[head--]`): the cursor backs away from the `head == tail` guard and
  re-decodes earlier bytes; the spin races the eventual bounds fault, which is
  why this member often lands `KILLED` instead.
- `CharsJsonIterator.parse:136` (escape skip `++i` → `--i`): cancels the for
  loop's own `++i`, pinning the cursor on the same backslash — a pure
  oscillation with no exit and no fault.
- `CharsJsonIterator.skipPastEndQuote:156` (`buf[head++]` → `buf[head--]`):
  same reversed-cursor family as `parseMultiByteString:575`.

**util**
- `JIUtil.escapeQuotesChecked:170` (do-while `++from` → `--from`): after an
  odd, already-escaped quote the backward `from` makes `indexOf('"', from)`
  re-find the same quote every pass (a negative fromIndex clamps to 0), so
  the loop never reaches `len`.
- `FieldMatcher.of:51` (`names.length << 2` → `>> 2`): collapses the table
  capacity below the entry count, and the linear-probe insert loop exits only
  on an empty slot or a duplicate name — a full table offers neither.

## Mutator-set trial (2026-07-21)

Per HARDENING.md ("the mutator set bounds what the ratchet can see"),
`EXPERIMENTAL_BIG_INTEGER` was trialed on every suite: `iterator` 1904 → 1904,
`numbers` 326 → 326, `util` 335 → 335 generated mutants — **zero fires**. The
`readBigDecimal`/`readBigInteger` paths construct their results from parsed
chars; no `add`/`multiply`-family arithmetic exists in mutated classes for the
mutator to rewrite. Left off (enabling a mutator that cannot fire is baseline
churn for nothing); re-trial if Big arithmetic is ever introduced.

## Mutator-set trial (2026-07-22)

`EXPERIMENTAL_NAKED_RECEIVER` (fluent calls returning their receiver are
expressions, invisible to `VoidMethodCallMutator`), via `pitestMutatorTrial
-PtrialMutators=EXPERIMENTAL_NAKED_RECEIVER`: `iterator` 16 generated — 11
killed by existing tests, 5 unkilled; `numbers` and `util` cannot fire (no
receiver-returning calls in their targets). **Enabled on `iterator` only.**
Of the 5 unkilled: 4 were dropped `skip()` calls on the default branches of
`readShortOr`/`readDoubleOr`/`readFloatOr`/`readBooleanOr` — genuinely
untested cursor positions, killed by extending
`TestNull.test_read_primitive_or_default_skips_and_positions` with
position-after reads across all widths (the long/int variants already had
them, which is what killed their mutants in trial); 1 is a `NO_COVERAGE` row
on `JsonIterParserFactory.loadParser`, joining the unreachable-in-harness
ServiceLoader family below.

## Multiset re-triage (2026-07-23)

sava-build 21.5.9's verify compares baselines as multisets, which exposed
sibling survivors the earlier unique-row comparison had collapsed into their
accepted twins — 4 in `iterator`, 14 in `numbers`, 2 in `util`, every one
sharing `class,method,line,mutator,status` with an already-accepted row.
Re-triaged individually:

- **Killed** (real gaps found by the expansion): the lenient-literal-skip
  directions in `BaseJsonIterator.skipTrue`/`skipLiteral` — leniency on the
  `'r'`/`'u'` checks silently *accepted* corrupt documents (`tque`, `trqe`),
  and the truncated-tail `skipLiteral` siblings threw at the wrong offset or
  through `peekChar`'s EOF funnel instead of `expected <literal>` at the
  first divergence. All killed by
  `TestSkip.test_skip_corrupted_literals_reject_at_exact_offset` (every
  corruption position × every literal × fast and truncated paths, exact
  message + offset, all sources); the previously accepted rows at those keys
  died with them and left the baseline.
- **Accepted**: the `matchPattern` over-detection siblings (see the
  multibyte-scan family note above) and the `DoubleParser` /
  `JHex$INIT_DIGITS` sibling occurrences, whose family arguments
  (slow-path routing to the `Double.parseDouble` oracle with the dangerous
  directions observably killed; static-initializer tables built before
  mutants activate) are line-level and cover every operand direction at
  their coordinates. The baselines now carry one row per sibling mutant.

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
(`JsonIterParserFactory`, 6 NO_COVERAGE — 5 original plus the 2026-07-22
`NakedReceiverMutator` on the `ServiceLoader.load(...).stream()` chain): the
load-success path needs a
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
