# Mutation-testing baseline & triage policy

Each `pitest<Suite>` run is finalized by `pitest<Suite>Verify`, which diffs the
run's unkilled mutants (`SURVIVED` and `NO_COVERAGE`) against the accepted
baseline in `<suite>-accepted.csv` and **fails on anything new**. Baseline row
format: `class,method,mutator,status`, optionally followed by a `# line N`
tag recording where the mutant was last observed and/or a `# <label>` note
naming the row's acceptance family.

A new unkilled mutant has exactly three legal outcomes:

1. **Kill it** — add or strengthen a test. Name the property the code is
   externally meant to hold and an oracle independent of the implementation
   before writing the assertion; if that oracle contradicts current behaviour,
   the mutant found a production bug and is owed a failing regression test plus
   a source fix, not a passing assertion that pins the bug. See AGENTS.md,
   "A mutant is a question, not a specification". Prefer asserting the property
   the mutant breaks (position after a skip, exact error context, allocation
   bounds) over restating the implementation.
2. **Refactor** — restructure so the mutant cannot exist.
3. **Accept it knowingly** — re-run through the suite's named baseline writer
   and record the reason below. `hardeningHelp` lists them, and which one is
   not a detail: these baselines are already provenance-bound, so a
   reviewed *additive* acceptance is `pitest<Suite>BaselineUnion`, which
   appends the new rows and leaves every existing one alone.
   `pitest<Suite>BaselineUpdate` rewrites the record from the report and drops
   rows the run did not match, which on a baseline carrying flip insurance and
   argued siblings loses evidence rather than adding it — reach for it only
   when a reviewed whole-record rewrite is the intent.
   `pitest<Suite>BaselineRebase` is for a reviewed PIT, PIT-plugin/tool-artifact,
   ArcMutate-base, or certificate transition only. Acceptance is for mutants
   that are *equivalent with respect to observable behavior*, not for "hard to
   test"; the structural `NO_COVERAGE` cases below have separate reachability
   arguments, not claims of observed equivalence. The refresh seeds
   each new row `# untriaged`. Family arguments live below; a missing label
   or a recognized legacy label does not establish completed triage. The
   installed writers preserve existing notes (`BaselineRetag` changes
   only line metadata), so there is no supported family-label editing task.
   Do not hand-edit rows to silence label warnings; reconcile their method
   and semantic branch against the current argument instead.

Line numbers left the baseline key in sava-build 21.5.20 (these files were
migrated with `migrateMutationBaselines` on 2026-08-01, row identities
unchanged), so an unrelated edit to a mutated file churns nothing. What is
left is a `# line N` tag — review metadata, not identity, refreshed with
`BaselineRetag` when metadata alone needs updating — plus a **line-drift
advisory** when a key is unkilled only at lines no tag names — the code an
acceptance argues about moved, or a new mutant landed under an old
acceptance. Re-read the argument below when that fires; the price of the
line-less key is that a same-key swap (one mutant killed, another born at the
same `class,method,mutator,status`) is otherwise invisible. See sava-build's
`HARDENING.md`. The baseline is a **multiset**:
identical rows are sibling mutants of one compound condition (one per
operand or branch direction), so duplicate lines are legal and must never be
hand-deduped.

Incremental analysis (PIT history) needs arcmutate, which is **not eligible
here** — the certificate is scoped to `software.sava.*` packages and this
library's are `systems.comodal.jsoniter.*`, so no run of these suites can be
`[history]`-backed (`HARDENING_NOTES.md` §"No ArcMutate acceleration here").
The rule it would otherwise impose still binds every writer decision, because
the other way to get a report that cannot support one is cheap and local: a
`[history]` report may check the ratchet but can never add, remove or relabel
an accepted or timeout row, and neither can a `-PmutateOnly` scoped report,
which the plugin diverts to `build/reports/pitest-scoped/` and marks
`ratchet skipped`. Iterate scoped, then re-run the suite unscoped with
`-PnoMutationHistory` before touching any record here.

The fuzz seed corpora replay deterministically in the unit suite via the
plugin-generated `<Harness>SeedReplayTest` classes (one per fuzz target), so
newly committed seeds — including promoted fuzz findings — face PIT's mutants
automatically. The suites' `targetTests` include `*SeedReplayTest` so the
replays participate as killers.

## Acceptance arguments in force

The baseline CSVs carry exact keys and sibling multiplicities. The families
below name the affected methods, the reason for retaining their records,
and the oracle and changes that require renewed review. A passing ratchet
is not independent proof that an argument covers every accepted sibling.

### Slow-path / alternate-path routing

**Reason** — both paths are result-identical, the
mutant only changes which one runs (performance/allocation, not behavior):
- `DoubleParser.parse` scan mutants that route more inputs to `slow()`, which
  delegates to `Double.parseDouble` — a bit-exact oracle by construction.
  Empirically confirmed over a ~1M-value deterministic corpus.
- `readInt`/`readLong` fast-vs-slow path selection and re-routing of
  already-terminated digit runs; `BytesJsonIterator.parse` SWAR-word-loop vs
  `parseTail` selection; byte field-name/equality guards forcing a full-name
  scalar parse; `CharsJsonIterator.parse`/`parseFieldName` forcing
  `handleEscapes` on escape-free content (content-identical copy);
  `CharsJsonIterator.parseFieldEquals` and `parseFieldEqualsSlow` forcing
  full-name decode instead of the prefix path;
  `FieldMatcher.match` ascii-check boundary forcing the UTF-8 ground-truth
  path; `JIUtil.escapeQuotes*` prefix-fast-forward skips (the generic scan
  re-finds the same first special char). `BytesJsonIterator.parseTail` and
  `skipPastSingleByteEndQuote` can route an additional `]` character through
  the general parser without changing the returned text or cursor.

Oracle: decoded value, cursor position and rejection contract of the alternate
path; `Double.parseDouble` supplies the independent numeric oracle. Invalidated
by a path that changes rejection, position, output or allocation complexity.
Routing fewer malformed inputs to the reference parser is not covered.
In `BytesJsonIterator.parseFieldEquals`, forcing the escape-check branch
into whole-name decoding is routing only. Disabling that branch is a
different direction: an escaped-name witness must still reach decoding.

### Arithmetic identities

- `InvertNegs` on the `MIN_VALUE` overflow checks: `-val == MIN` ⇔
  `val == MIN` in two's complement.
- `DoubleParser` `×10⁰ ≡ ÷10⁰`, Eisel–Lemire vs Clinger agreement at the
  2⁵³−1/2²⁴−1 boundaries, refinement/tie-range guards gated by exactness
  tests, `reduceScale(v, 0)` identity; the escape-parity `Incr` mutants in
  `JIUtil`, label `# escape parity`: every `escapes` counter is consumed only
  via `(escapes & 1) == 0`, and flipping the increment direction maps a run
  of n to −n (scan loops) or 2−n (checked backward counts) — same parity
  either way, so the direction is unobservable.

Oracle: two's-complement arithmetic and parity identities; numeric boundary
tests compare the JDK parsers. Invalidated by a wider consumer of the escape
count, changed numeric precision or rounding rules, or different overflow guards.

### DoubleParser rounding and saturation

Members: `DoubleParser.parse`'s exponent-clamp equality boundary, and the
retained routing/refinement/rounding directions in `computeDouble` and
`computeFloat`. The argument is directional; it does not accept all mutants
sharing those methods or mutator names.

- Fast-scan mutants in `parse` retain the original input window when they
  fall back to `slow`. Narrowed digit bounds, early termination and rereading
  a fraction/exponent digit leave trailing content that triggers the same
  reference parse. Increasing the digit count or rejecting the 19-digit fast
  case also selects fallback. This relies on in-bounds nonnegative offsets
  and the unchanged trailing-content validation.
- At exponent-clamp equality, one additional decimal accumulation still
  saturates to the same signed zero or infinity: a mantissa of at most 19
  decimal digits cannot offset an exponent near `0x10000000`.
- Clinger-boundary/forced-fallback directions move the `2^53-1` double or
  `2^24-1` float mantissa boundary to Eisel–Lemire. The conversion paths must
  agree bit for bit. Multiplication versus division at exponent zero is
  multiplication versus division by the table entry one.
- Forcing product refinement computes an additional limb even when the
  ambiguity guard says it is unnecessary. Outside that ambiguity the added
  precision cannot alter the extracted and rounded answer. This relies on
  the Eisel–Lemire masks and limb arithmetic, not simply fallback routing.
- Sending the minimum-normal binary exponent through the subnormal branch
  has a zero shift at that boundary and reaches the same implicit-bit and
  rounding encoding.
- For `computeFloat`'s decimal exponent `-65` boundary, a mantissa below
  `10^19` gives magnitude below `10^-46`, below half the smallest subnormal
  (`2^-150`); either direction rounds to signed zero.
- Expanded halfway-test exponent bounds cannot create an actionable exact
  halfway state outside the original domain. The lower-product comparison
  excluding one rather than including it relies on that value not coinciding
  with the actionable upper-product tie state. For float endpoint exponents
  `-17` and `10`, the normalized significand bounds and midpoint low bits
  likewise make the extra tie correction ineffective.

Oracle: exact JDK double/float bits, saturation and malformed-token rejection
(including exception messages). The recorded approximately million-value
fixed-seed corpus supports the conversion-path agreement. The tie/refinement
argument relies on the mask/product invariants; this cleanup does not claim
an additional exhaustive arithmetic sweep. Reassess after a power-table,
mask, precision, shift, bias, rounding predicate, mantissa bound, exponent
clamp or fallback-grammar change.

### Unreachable defense-in-depth

**Reason** — the guarded state cannot arise because an
earlier limit check already rejected it: slow-path wrapped-accumulator
`== 0` cases in `readIntSlowPath`/`readLongSlowPath`, the `scaleLong`
single-step wrap check, `FieldMatcher.hash` len == 8 word agreement,
capacity-sizing mutants in `FieldMatcher.of` (any power-of-two capacity greater than
field count matches identically), and the `numEscapes == 0` fallback return
in `CharsJsonIterator.parseFieldEqualsSlow` (`NO_COVERAGE`): both
entries into that method preclude it — the truncation entry throws inside
`parse(from)` before any return, and the backslash entry guarantees
`numEscapes >= 1`. Kept as defense against a future `parse` change; a
refactor that removes the branch retires the rows with it.

Oracle: the predecessor guards and public malformed-input tests. Reassess
if a caller bypasses them, buffer/refill semantics change, or the guarded
state becomes reachable. Capacity changes in `FieldMatcher.of` are covered
only while the table remains a power of two with an empty slot after every entry is inserted. An unknown
lookup relies on that empty slot to terminate.

### Allocation routing in iterator support paths

Members: `BaseJsonIterator.contextWindow`'s initial `StringBuilder` capacity
arithmetic, `BytesJsonIterator.widenToCharBuf`'s exact-fit extra growth, and
`CharsJsonIterator.handleEscapes`'s oversized temporary array and forced trim.
These change initial capacity or a bounded copy, while the append/trim logic
still selects the same content. They do not remove growth amortisation.

Oracle: error-context text, decoded string content and buffer-reuse tests.
Reassess if builder capacity becomes a reported value, temporary-array tails
become visible, size arithmetic can overflow for reachable inputs, or these
changes remove an allocation contract rather than a sizing hint.

### Truncated literal loops

Members: `BaseJsonIterator.skipLiteral`'s loop-boundary and forced-loop
mutants on the truncated-input branch. Every entry to this branch has fewer
remaining source characters than the literal requires. The inner tail guard
therefore throws before the loop could finish, including the extra-iteration
direction. It cannot accept a malformed token or change its first-error offset.

Oracle: `TestSkip.test_skip_corrupted_literals_reject_at_exact_offset`,
including every truncation position and literal. Reassess if the helper gains
an entry with enough remaining input, the tail check changes, input refills,
or error reporting moves outside the loop.

### Redundant end checks in numeric lookahead

Members: `BytesJsonIterator.peekChar` and `CharsJsonIterator.peekChar`
no-argument overloads' forced-read guard mutants. Their only caller is
`assertNotLeadingZero`, which returns before calling them when `head == tail`.
Removing the repeated end check cannot expose a read at the document end.

Oracle: that call graph and the truncated-number tests. Reassess on any new
caller, a changed predecessor end check, or incremental input/refill support.

### Quote escaping prefix and completion routing

Members: `JIUtil.escapeQuotes` and `escapeQuotesRemoveNewLines` prefix-arm
boundary/forced-generic mutants. A generic scan rediscovers the first quote
or newline; an empty prefix copy has the same output as the index-zero arm.
`escapeQuotesChecked` can route an `indexOf` miss through the full helper or
perform one extra `indexOf` at the end; both return the same input instance.
In `escapeQuotes`, the no-op conjunction can omit its `from == 0` test:
`dest == 0` means no quote has been emitted and therefore `from == 0` already.

Oracle: escaped-quote parity, newline stripping and same-instance no-op
assertions in `TestJIUtil`. Reassess if the generic scan starts with different
parity/state, prefix position advances without emitting output, or the end
lookup/no-op contract changes. This does not cover bypassing parity at a
quote preceded by exactly one backslash in `escapeQuotesRemoveNewLinesChecked`.

### FieldMatcher capacity and word agreement

Members: `FieldMatcher.of` capacity rounding/boundary/shift mutants,
`FieldMatcher.hash`'s eight-byte boundary, and `FieldMatcher.match`'s
ASCII/DEL checks that select UTF-8 fallback.

Reason: the accepted sizing directions retain a power-of-two table with
strictly more slots than names. Empty slots terminate unknown-name probes;
capacity equal to the name count is not sufficient. The eight-byte scalar
hash packs the same big-endian word as the word load, with no last-word
remainder. UTF-8 encoding preserves ASCII and DEL, so forcing fallback
changes routing rather than declared-name resolution.

Oracle: `TestFieldMatcher` checks declared indices, unknown names, duplicate
rejection and byte/char agreement. Reassess if load bounds, word endianness,
hash mixing, fallback encoding or reachable size-arithmetic limits change.
The `names.length << 2` to `>> 2` sizing mutant that fills the table is a
liveness timeout below, not an accepted capacity direction.

### Table construction must be tested after activation

`JHex$INIT_DIGITS.initDigits` and `ValueType$INIT_TYPES.initValueTypes`
initialize cached tables before PIT activates mutants in the minion. Public
lookup assertions alone therefore cannot detect changed builders. Those
mutants are not behavioural equivalents. `TestLookupTableInitialization`
invokes each private builder after activation and checks its returned table
against an independent per-entry mapping, including invalid entries.

Do not restore an initialization acceptance merely because the cached public
table passes. Reassess the fixture if helper signatures or module access
change; a fresh defining class loader is another way to exercise construction.

### Throw-terminated blocks

Members: `DoubleParser.parse` return-value mutants on `return slow(...)`
after a sign or exponent marker, and `BytesJsonIterator.parseFieldEquals` /
`CharsJsonIterator.parseFieldEquals` boolean-return mutants on truncation
bail-outs into `parseFieldEqualsSlow`.

Reason: PIT probes block ends, but these blocks always throw before the
mutated return can execute. Their `NO_COVERAGE` records are a structural
coverage limitation, not an untested contract or an equivalence claim.

Oracle: `TestDoubleParser.test_sign_and_exponent_marker_at_end_of_input`
and `TestDouble.test_specials` check JDK rejection/message parity for `-`,
`1e`, `1e±` and `1e±x`; `TestSkip.test_skip_until_truncated_field_name` checks
truncated field names. Streams are read fully, so `tail` is the document end.
Invalidate this argument if `slow` can return on these inputs, refilling is
introduced, truncation recovers instead of throwing, or PIT changes its probes.

### Multibyte scan paths

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

Oracle: the recorded exhaustive sweeps and the content/position sweep tests.
Reassess if surrogate classification, error context, cursor recovery after
rejection, or word-loop guard ordering changes.

### Constant-factor allocation routing

Label: `# allocation routing`. Members: `JIUtil.escapeJson` arithmetic
mutants in its initial capacity, and `JIUtil.ensureCapacity`'s exact-fit
boundary (`needed <= out.length` to `<`). A different initial size or one
unnecessary exact-fit copy preserves output and changes only a bounded
allocation factor. Every shortfall is corrected before writing.

Oracle: `TestJIUtil.testEscapeJsonGrowsPastInitialCapacity` checks output and
capacity shortfalls; the growth-complexity tests below guard amortisation.
Reassess on new size limits, changed copy boundaries, or an allocation
contract that distinguishes these constant factors. Removing doubling
headroom is explicitly outside this family.

### Word pattern over-detection

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

### ASCII word-loop tail handling

Members (`skipPastEndQuote`, `parseString`,
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

Oracle: the independent expected content and post-read positions in the
`TestString`/`TestSkip` length sweeps, and JDK base64 decoding. Reassess if
word/scalar paths stop sharing validation, word width/alignment changes,
or the decoder stops sizing valid output exactly.

## Reusable buffer bounds and amortisation

Label: `# bounded reusable buffer`. Members: `BytesJsonIterator.doubleReusableCharBuffer`
guard boundary (`>=` to `>`) and never-fire mutants. Every decode pass consumes
at least as many source bytes as it appends chars; input is never refilled
mid-parse. A grow request at or beyond `tail` is therefore unreachable in
unmutated code, including malformed input. These guard mutants are accepted
because the state they distinguish cannot occur. `widenToCharBuf`'s exact-fit
boundary is constant-factor allocation routing: it may grow an already
sufficient buffer, preserving field content and reuse thereafter.

Oracle: the input-consumption bound plus
`TestMultiByteScanEdges.test_char_buffer_growth_through_surrogate_split` and
`test_field_buffer_is_reused_when_the_name_already_fits`. Reassess if refill,
expanding transformations, append-before-check ordering or caller invariants
change. Forced/negated guards and cursor reversals are not accepted by this
argument: the bound makes non-advancing parse loops throw rather than exhaust
memory.

Allocation-complexity mutants are not equivalents. These tests retain the
empirical separation that guards the family:

- `TestAllocation.test_char_buffer_grows_amortised_not_per_character` decodes
  54 chars from a two-char buffer with an 8192-byte bound. Amortised growth
  costs hundreds of bytes; per-character doubling is outside the contract.
  The document clamp also makes repeated unnecessary growth throw, so a
  content assertion can detect that direction independently.
- `test_field_name_widening_keeps_doubling_headroom` walks field names of
  lengths 64..192 through `testObject`: amortised growth reallocates three
  times, exact sizing on all 129. The 8192-byte bound separates about 900
  bytes from about 33000. `applyCharsAsInt` is not a substitute: its overload
  does not reach `widenToCharBuf`.
- `test_escape_growth_doubles_rather_than_sizing_to_each_escape` uses 512
  control characters and a 64-KiB bound against per-escape sizing's megabytes.
  Halving `ensureCapacity`'s doubling shift is a complexity change even when
  the resulting text is identical.

Measured results escape through volatile sinks. Do not weaken these bounds
on a survivor before checking the measured call path and escape analysis.

## Timed-out mutants (audited set)

`TIMED_OUT` is detected — these mutants never enter a baseline — but the
watchdog observed slowness, not wrongness: for exactly these mutants the
ratchet cannot see a weakened covering assertion, because a timeout keeps
"detecting" no matter what the test asserts. The summary's
`N timed out (load-dependent)` is therefore an audited set, not a count: every
member is listed here with the structural cause that makes it spin, and a
mutant timing out that is *not* on this list is something a reviewer stops on.
Membership is machine-checked — `iterator-timeouts.csv` / `util-timeouts.csv`
hold the `class,method,mutator` keys — and `numbers` has never timed out, so it
has no file and the check is inert for that suite.

Each row also carries a reviewed `# cause:` category, and only **one** of them
certifies. `cause:liveness` means the mutated path has no finite completion
guarantee of its own: it does not end, so the watchdog is the only thing that
*can* report it, and the timeout is honest evidence. The other three are
findings, not classifications to settle on — `cause:resource` for a path that
does complete but at a cost the watchdog beats, `cause:harness` for a
demonstrated finite covering-path/watchdog race being repaired, and
`cause:untriaged` for a seeded row nobody has read yet. A run under
`-PstrictTimeoutAudit` — which `hardeningCertify` forces — fails on all three.

Cause is key-level: the argument must cover every timed-out sibling under
the method and mutator. A new sibling can match existing membership, so
review changes to the actual loops rather than trusting a line tag.
Skip loops that write nothing can be liveness failures; parse loops that
append are finite when the document bound forces rejection. Finite faults
are not accepted as liveness merely because they race the watchdog.

**iterator — cause:liveness**

- `BaseJsonIterator.reduceScale`, two mutants (`scale--` → `scale++`;
  loop condition → `true`): the counter crossing the negative `scaleLimit` is
  the loop's only exit; both remove it and the divide loop spins on a settled
  quotient of 0. Long division allocates nothing, so nothing else can end it.
- `BaseJsonIterator.skipObject` (scan cursor `i++` → `i--`): the
  `i == tail` bound is an equality a backward walk never meets, and the
  string-skip re-entry (`i = head - 1` after `skipPastEndQuote()`) pulls the
  cursor forward again, so on any object carrying a field name the cursor
  cycles over a fixed span instead of running off the front. Nothing is
  allocated inside the cycle.
- `BaseJsonIterator.skipObject` (string re-entry `head = i + 1` →
  `head = i - 1`, admitted 2026-08-16): the assignment puts
  `skipPastEndQuote()` on the character *before* the opening quote, so on two
  adjacent quotes — an empty string — it consumes only the first and returns
  `head = i`, and `i = head - 1` followed by the loop's `i++` restores the
  cursor exactly. `i` never reaches the `i == tail` bound, which is the loop's
  only exit. Transcribed and run outside the codebase over `{"":0}`: 500,000,000
  iterations with `i` and `head` pinned at 2 and `level` never moving. It is a
  skip, so the loop body writes nothing and no allocation can end it either.
  Surfaced by `y_object_empty_key.json` in the JSONTestSuite corpus — nothing
  here had run `skip()` across an empty-string key before. The corpus case
  remains a witness for this input class.
  There is no accepted `SURVIVED` row under this key. Object-string tests can
  reject the mutated result before the minion reaches the endless witness,
  yielding `KILLED`; that does not make the nonterminating input disappear.
  Keep the audited timeout membership while this endless witness exists;
  a killed-first observation does not prove the loop has been repaired.
- `BytesJsonIterator.skipPastMultiByteEndQuote` (escape-dispatch
  `buf[head++]` → `buf[head--]`, admitted 2026-08-03): the reversed cursor
  lands back on the backslash's *own* index, so the next pass reads the same
  `\` at the loop head, re-enters the same branch and restores the same state — a
  fixed point with no exit and no fault. It is a skip, so the loop body writes
  nothing: transcribed and run outside the codebase on 2026-08-15 it completed
  500,000,000 iterations with `head` pinned and zero allocation. What makes the
  *member* flap is the input, not the loop: only the eight simple escapes
  (`b t n f r " / \`) spin, because the `u` arm's four `head++` hex reads step
  past the backslash and hand `JHex.decode` the `\` itself, which throws. So a
  covering test carrying `\uXXXX` kills it while one carrying `\n` hangs it, and
  the member reads `TIMED_OUT` or `KILLED` according to which the minion reaches
  first — observed both ways within an hour on 2026-08-03. Removing the timeout
  would therefore mean removing a legitimate covering input, not fixing a
  harness bound. The sibling reversal at the loop head is reliably `KILLED`, which is
  the contrast worth keeping: at the loop head a backward cursor immediately
  misreads and the scan diverges observably, while at escape dispatch it is invisible
  because the loop never advances at all. The continuation-byte read has
  the same fixed point under the same key and the same liveness token.
- `CharsJsonIterator.parse` (escape skip `++i` → `--i`): cancels the for
  loop's own `++i`, pinning the cursor on the same backslash — a pure
  oscillation with no exit and no fault. `numEscapes` increments and wraps;
  nothing is allocated.
- `CharsJsonIterator.skipPastEndQuote` (`buf[head++]` → `buf[head--]`):
  the same allocation-free fixed point as `skipPastMultiByteEndQuote`, and
  for the same reason — this is the chars-source *skip* path, so the loop body
  writes nothing. `parseMultiByteString` instead appends and is bounded
  by the document; that finite fault is not this liveness family.

**util — cause:liveness**

- `JIUtil.escapeQuotesChecked` (do-while `++from` → `--from`): after an
  odd, already-escaped quote the backward `from` makes `indexOf('"', from)`
  re-find the same quote every pass (a negative fromIndex clamps to 0), so
  the loop never reaches `len`. `indexOf` and `charAt` allocate nothing.
- `FieldMatcher.of` (`names.length << 2` → `>> 2`): collapses the table
  capacity below the entry count, and the linear-probe insert loop exits only
  on an empty slot or a duplicate name — a full table offers neither. The probe
  walks a fixed `int[]`; nothing grows.

**On retiring a member.** `skipPastMultiByteEndQuote` sits on the quiet counter
and is not retired on that advisory alone. The flap is a per-mutant test-order
race over a genuinely endless loop, so the next run that reaches the `\n` input
before the `\uXXXX` one times out again, and with the member retired that would
be an unaudited newcomer that `hardeningCertify`'s forced strict audit fails
hard. A one-in-N member is exactly what an audited set is for. Retire it only on
evidence the race is gone: the `\n`-carrying covering test removed, or the escape
dispatch restructured so the reversed cursor can no longer land on its own
backslash.

Repair is the other route out. For a parse loop that appends, the document
bound licenses retirement only when every pass returns, throws, breaks or
appends a char, and appends stop at `tail`. That proves termination for every
cursor-reversal sibling under the line-less key; enumerating known spinning
sites does not. Retire stale timeout membership only after the installed
history-free/provenance checks permit it; quiet observations and coordinates
absent from the population have different retirement rules.

## Mutator measurements that determine configuration

### Big-number arithmetic trial (2026-07-21)

Per HARDENING.md ("the mutator set bounds what the ratchet can see"),
`EXPERIMENTAL_BIG_INTEGER` was trialed on every suite: `iterator` 1904 → 1904,
`numbers` 326 → 326, `util` 335 → 335 generated mutants — **zero fires**. The
`readBigDecimal`/`readBigInteger` paths construct their results from parsed
chars; no `add`/`multiply`-family arithmetic exists in mutated classes for the
mutator to rewrite. Left off (enabling a mutator that cannot fire is baseline
churn for nothing); re-trial if Big arithmetic is ever introduced.

### Fluent receiver trial (2026-07-22)

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
them, which is what killed their mutants in trial); 1 was a `NO_COVERAGE` row
on `JsonIterParserFactory.loadParser`, killed with the rest of the
ServiceLoader family on 2026-08-02.
