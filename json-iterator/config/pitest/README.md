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
   not a detail: these baselines are already bound and fully triaged, so a
   reviewed *additive* acceptance is `pitest<Suite>BaselineUnion`, which
   appends the new rows and leaves every existing one alone.
   `pitest<Suite>BaselineUpdate` rewrites the record from the report and drops
   rows the run did not match, which on a baseline carrying flip insurance and
   argued siblings loses evidence rather than adding it — reach for it only
   when a whole-record rewrite is the intent (the 2026-08-01 pass is the one
   time that was true here, and it took a per-key diff first).
   `pitest<Suite>BaselineRebase` is for a reviewed PIT/toolchain transition
   only. Acceptance is for mutants that are *equivalent with
   respect to observable behavior*, not for "hard to test". The refresh seeds
   each new row `# untriaged`; finishing triage means replacing that with a
   short family label whose argument lives in a section below (mentioned there
   as `# <label>` — the verify and debt listing warn on labels with no such
   mention, so a typo can't open a phantom family). Rows accepted before label
   seeding arrived (sava-build 21.5.10) carry no label and print as
   `unlabeled` in the verify's per-label counts — their arguments are complete
   below; label a row with its family when touching it rather than by bulk
   inference.

Line numbers left the baseline key in sava-build 21.5.20 (these files were
migrated with `migrateMutationBaselines` on 2026-08-01, row identities
unchanged), so an unrelated edit to a mutated file churns nothing. What is
left is a `# line N` tag — review metadata, not identity, rewritten only by a
full `BaselineUpdate` or by a `BaselinePrune` whose removal-candidate preview
is empty, not by every writer — plus a **line-drift
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
field count matches identically), and the `numEscapes == 0` fallback return
in `CharsJsonIterator.parseFieldEqualsSlow` (line 434, 2 NO_COVERAGE): both
entries into that method preclude it — the truncation entry throws inside
`parse(from)` before any return, and the backslash entry guarantees
`numEscapes >= 1`. Kept as defense against a future `parse` change; a
refactor that removes the branch retires the rows with it.

**Static-initializer table** (`JHex$INIT_DIGITS`): built once per PIT minion
JVM before mutants activate, so table-construction mutants are unkillable by
construction. The per-call `decode` mutants all die.

**Throw-terminated blocks** — `NO_COVERAGE` that no test can ever clear,
because PIT's block coverage probes a block at its *end*: a `return f(...)`
whose call throws for every input reaching it never completes its block, so
the line reads unreached no matter what executes it, and its return-value
mutants can never change status (the throw happens before the mutated return).
Established empirically on 2026-08-02: `TestDouble` had fed quoted `"1e"`
through `readDouble()` since the parser landed, and `DoubleParser.parse:136`
read `NO_COVERAGE` in every report regardless. The rows, all
executed by tests that assert the throw:
- `DoubleParser.parse` 105/136/142/147 — `return slow(...)` for inputs ending
  after a sign or exponent marker (`-`, `1e`, `1e±`, `1e±x`); the reference
  parser throws for every such input, with message parity pinned by
  `TestDoubleParser.test_sign_and_exponent_marker_at_end_of_input` and the
  wrapped-message loop in `TestDouble.test_specials`.
- `BytesJsonIterator.parseFieldEquals` 452/466 and
  `CharsJsonIterator.parseFieldEquals` 407 — truncation bail-outs into
  `parseFieldEqualsSlow`, whose `parse` reports the incomplete document (no
  source refills: streams are read fully upfront, so `tail` is always the
  document end). Executed by `TestSkip.test_skip_until_truncated_field_name`.

The retired name of this family was **NC→SURVIVED traps** — the fear that
covering these lines would surface the return-value mutants as new SURVIVED
entries. That mechanism was wrong: the mutants stay `NO_COVERAGE` under any
test, so the deliberate test absence bought nothing and cost real contract
coverage — closing it killed 10 accepted SURVIVED siblings whose routing
arguments only held while malformed and truncated inputs went unasserted
(see the 2026-08-02 section). The one honest trap instance remains history:
the `JIUtil.escapeQuotes*` branches, taken knowingly on 2026-07-26 — the
backslash-run tests killed 20 accepted mutants at the cost of two stranded
`# escape parity` increments. Public-API branches with no test were worth
more than the trap avoided, both times.

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
  - `escapeJson:83` `new char[len + 8 + (len >> 3)]`, two MathMutator siblings.
    Only the *initial* capacity moves; a smaller one just grows sooner and a
    larger one wastes a little. The third sibling (`len - 8`) is killed — it
    goes negative on short input and throws.
  - `ensureCapacity:135` `needed <= out.length` → `<`: at exact equality the
    mutant grows a buffer that already fits. Same array contents.
  - `ensureCapacity:138` `out.length << 1` → `>> 1`: `Math.max(needed, half)`
    is then always `needed`, so the buffer is sized exactly instead of doubled —
    correct output, just more frequent growth.
  **Narrowed 2026-08-05 to constant-factor sizing only.** The fourth row of this
  family — `ensureCapacity:138` `Math.max(needed, out.length << 1)` -> `>> 1` —
  was *not* allocation routing: it drops the doubling headroom, so a densely
  escaped input reallocates once per escape instead of three times in total,
  which is a complexity-class change wearing an identical output.
  `TestAllocation.test_escape_growth_doubles_rather_than_sizing_to_each_escape`
  kills it (512 control characters, 64 KiB bound against megabytes); util
  43 -> 42 rows, 351 -> 352 of 394. The three rows that remain are genuine:
  `ensureCapacity:135` grows once more than needed at exact equality, and the
  two `escapeJson:83` initial-capacity mutants pick a different constant. Those
  cost a bounded multiple, never a different growth curve, so no bound
  separates them from correct behaviour without becoming the thin margin this
  file warns about.
  Deliberately not chased with `TestAllocation`: per AGENTS.md the allocation
  harness is a last resort, and these are precisely the "incidental
  micro-optimization only an allocation bound could observe" case it names as
  accept-worthy. The *observable* directions here are killed —
  `TestJIUtil.testEscapeJsonGrowsPastInitialCapacity` kills both under-request
  mutants on line 92 (`n + span - 6` runs off the end of a 64-control-character
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

## Amortised char-buffer growth (2026-08-05)

`BytesJsonIterator` guards every `charBuf` append with
`if (charBuf.length == j) doubleReusableCharBuffer();`. The *never-grow*
direction of each guard corrupts output and dies to
`TestMultiByteScanEdges.test_char_buffer_growth_through_surrogate_split`. The
*always-grow* direction is content-identical — the string still decodes
correctly, it just allocated 2^N getting there — so no `assertEquals` can see
it. Lines 659 and 663 therefore sat in the accepted baseline as survivors, and
line 672 was "killed" only by `TestString.test_long_string` exhausting the heap,
which races PIT's watchdog: it timed out under a loaded certification on
2026-08-05 and read `KILLED` on the same commit solo.

`TestAllocation.test_char_buffer_grows_amortised_not_per_character` closes the
family by asserting the property directly — decoding 54 chars from a 2-char
buffer allocates a few hundred bytes, where per-character doubling allocates
about a megabyte. Baseline 121 -> 119; `iterator` 1798 -> 1800 of 1919.

`widenToCharBuf:200` `MathMutator` (`Math.max(len, charBuf.length << 1)` ->
`>> 1`) closed the family on the same day. It is content-safe because
`Math.max` still admits `len`; what it removes is the doubling headroom, so the
cost is repeated reallocation across a *sequence* of widening reads rather than
within one — invisible to any single-read assertion.
`TestAllocation.test_field_name_widening_keeps_doubling_headroom` walks one
object whose field names grow 64..192: amortised growth reallocates three times,
exact sizing on all 129. It uses `testObject` deliberately, because the
`FieldBufferPredicate` contract allocates nothing per field, so buffer growth is
the only thing the counter can see — the first attempt went through
`applyCharsAsInt`, which reaches a different `parse` overload that never calls
`widenToCharBuf`, and the mutant survived it. Baseline 119 -> 118;
`iterator` 1800 -> 1802 of 1919.

The "always-grow is content-identical" premise above held only while growth was
unbounded; see the next section, which removed it. Both tests stay — they now
guard *amortisation*, which is still invisible to any `assertEquals`, rather than
being the only thing that could see the mutant at all.

## Bounding the reusable char buffer (2026-08-15)

`doubleReusableCharBuffer` doubled with no clamp, so past 2^30 chars
`charBuf.length << 1` overflowed negative — a real `NegativeArraySizeException`
independent of any mutant. It is now bounded by the document: every decode step
consumes at least as many source bytes as it writes chars, `buf` is never
refilled mid-parse (`reset(InputStream)` reads the stream whole), and every
caller grows only when the buffer is exactly full — so a grow request at or past
`tail` cannot happen for any input, valid or malformed, unless the cursor stopped
advancing. `widenToCharBuf` takes the same clamp.

That closed both non-certifying timeout findings, which is why they are no longer
in the audited set:

- `parseMultiByteString` `MathMutator`, every cursor reversal in the method —
  previously `cause:harness`. The reversed cursor still spins, but the loop now
  ends in a `JsonException` within milliseconds instead of racing PIT's watchdog
  against the heap. All of them read `KILLED`.
- `widenToCharBuf` `RemoveConditionalMutator_ORDER_IF` — previously
  `cause:resource`, and simultaneously an accepted `SURVIVED` row. Forcing the
  grow branch now allocates a bounded amount, so
  `test_field_name_widening_keeps_doubling_headroom` reaches its assertion
  instead of the heap ceiling. Killed, and the accepted row pruned.
  Its `ConditionalsBoundaryMutator` sibling (`>` -> `>=`) is unaffected and
  stays accepted: one extra grow at the exact-fit boundary is far inside the
  8192-byte bound.

`TestMultiByteScanEdges.test_field_buffer_is_reused_when_the_name_already_fits`
was added alongside, asserting the same widening contract through buffer identity
rather than byte counts — `TestAllocation` is skipped entirely on a JVM without
thread allocation counters, and this kill should not depend on that capability.

Two new accepted survivors, label `# bounded reusable buffer`: the guard's
boundary direction (`>=` -> `>`) and its never-fire direction. Both are
equivalent by construction — the guarded state is unreachable, so no test can
distinguish them — which is the disclosed price of the bound. The other two
directions are killed: forcing the guard throws on every grow, and negating it
throws on the first.

Baseline 118 -> 119; audited timeouts 10 -> 8; `iterator` 1802 -> 1803 of 1922,
timed out 7 -> 5.

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

The distinction that matters here is **allocation**, and it splits this repo's
otherwise identical reversed-cursor family in two. `buf[head++] -> buf[head--]`
pins the cursor on the backslash it just read, so the loop never advances. In a
*skip* path nothing is written and the loop simply never ends: liveness. In a
*parse* path the same non-advancing loop appends one char to `charBuf` every
pass and doubles the buffer, so it ends after all — by exhausting the heap.
That is a finite fault racing the watchdog, and it is not liveness however much
the loop looks like it. Measure before classifying: transcribe the loop, run
it, and see whether it stops.

Keys are deliberately line-less, so drift cannot churn membership, and `# line`
tags are diagnostic metadata for review only. Source-line movement never warns,
never fails, and never requires re-anchoring — adding a method or reflowing an
expression above a mutated line is not a hardening record change. What the
line-less key *does* cost is resolution: a new timed-out mutant inside an
already-audited method+mutator matches the existing member silently. That is why
each cause below names the line it argues about and, where the key covers more
than one mutant, says so — re-read those lines whenever the code at them
changes, because a clean run certifies "no new method+mutator", not "no new
mutant". Cause is key-level too: a liveness token claims every sibling under its
key, so a key that mixes liveness with a finite cause cannot be recorded
honestly at all until the site is split or removed.

Members flip `KILLED`↔`TIMED_OUT` run to run, so per-run counts sit at or below
the set size — but a *finite* flip is a repair item, not a recorded cause. The
2026-08-15 history-free observations had 5 of the 6 `iterator` members time out
(`skipPastMultiByteEndQuote` did not) across three consecutive runs.

As of 2026-08-15 — 8 members, 6 `iterator` + 2 `util`, `numbers` none, and all
eight are honest liveness. Two `BytesJsonIterator` members were retired the same
day by repair rather than by a quiet streak; the change that closed them is
recorded under "Bounding the reusable char buffer (2026-08-15)".

**iterator — cause:liveness**

- `BaseJsonIterator.reduceScale:1021`, two mutants (`scale--` → `scale++`;
  loop condition → `true`): the counter crossing the negative `scaleLimit` is
  the loop's only exit; both remove it and the divide loop spins on a settled
  quotient of 0. Long division allocates nothing, so nothing else can end it.
- `BaseJsonIterator.skipObject:1122` (scan cursor `i++` → `i--`): the
  `i == tail` bound is an equality a backward walk never meets, and the
  string-skip re-entry (`i = head - 1` after `skipPastEndQuote()`) pulls the
  cursor forward again, so on any object carrying a field name the cursor
  cycles over a fixed span instead of running off the front. Nothing is
  allocated inside the cycle.
- `BaseJsonIterator.skipObject:1127` (string re-entry `head = i + 1` →
  `head = i - 1`, admitted 2026-08-16): the assignment puts
  `skipPastEndQuote()` on the character *before* the opening quote, so on two
  adjacent quotes — an empty string — it consumes only the first and returns
  `head = i`, and `i = head - 1` followed by the loop's `i++` restores the
  cursor exactly. `i` never reaches the `i == tail` bound, which is the loop's
  only exit. Transcribed and run outside the codebase over `{"":0}`: 500,000,000
  iterations with `i` and `head` pinned at 2 and `level` never moving. It is a
  skip, so the loop body writes nothing and no allocation can end it either.
  Surfaced by `y_object_empty_key.json` in the JSONTestSuite corpus — nothing
  here had run `skip()` across an empty-string key before, which is why 1922
  mutants and a saturated fuzz campaign both missed it.
  **This key also holds an accepted `SURVIVED` row at the same line, and that
  row is now dead evidence rather than flip insurance**: a non-terminating
  mutant has no speed at which it survives, so the row can never match again.
  It is left in place because no plugin writer removes a preserved-timeout row
  and a record deletion is the maintainer's call — but it is the repair item
  AGENTS.md names, and repair is unavailable here because the watchdog is the
  only thing that can detect a loop with no exit.
- `BytesJsonIterator.skipPastMultiByteEndQuote:709` (escape-dispatch
  `buf[head++]` → `buf[head--]`, admitted 2026-08-03): the reversed cursor
  lands back on the backslash's *own* index, so the next pass reads the same
  `\` at line 683, re-enters the same branch and restores the same state — a
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
  harness bound. The sibling reversal at line 683 is reliably `KILLED`, which is
  the contrast worth keeping: at the loop head a backward cursor immediately
  misreads and the scan diverges observably, while at 690 it is invisible
  because the loop never advances at all. The continuation-byte read at line 737
  is the same fixed point under the same key and the same liveness token.
- `CharsJsonIterator.parse:136` (escape skip `++i` → `--i`): cancels the for
  loop's own `++i`, pinning the cursor on the same backslash — a pure
  oscillation with no exit and no fault. `numEscapes` increments and wraps;
  nothing is allocated.
- `CharsJsonIterator.skipPastEndQuote:156` (`buf[head++]` → `buf[head--]`):
  the same allocation-free fixed point as `skipPastMultiByteEndQuote:709`, and
  for the same reason — this is the chars-source *skip* path, so the loop body
  writes nothing. **Not** the same family as `parseMultiByteString:575`, which
  an earlier revision of this file claimed; that one appends.

**util — cause:liveness**

- `JIUtil.escapeQuotesChecked:170` (do-while `++from` → `--from`): after an
  odd, already-escaped quote the backward `from` makes `indexOf('"', from)`
  re-find the same quote every pass (a negative fromIndex clamps to 0), so
  the loop never reaches `len`. `indexOf` and `charAt` allocate nothing.
- `FieldMatcher.of:51` (`names.length << 2` → `>> 2`): collapses the table
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

The other route out is repair, and it is what closed the two `BytesJsonIterator`
members on 2026-08-15. Note what licenses their removal: not an enumeration of
the sites that used to spin. The key is line-less, so retiring it asserts that
*no* mutant under that method and mutator can time out — and an enumeration would
have missed `parseMultiByteString`'s 2-byte continuation read, a third spinning
cursor nobody had listed. The licence is a loop bound instead: every pass of that
loop returns, throws, breaks, or appends at least one char, and appends now stop
at `tail`, so the loop terminates no matter which cursor a mutation reverses.
Prefer a structural argument of that shape over a site list whenever a line-less
key is retired.

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
them, which is what killed their mutants in trial); 1 was a `NO_COVERAGE` row
on `JsonIterParserFactory.loadParser`, killed with the rest of the
ServiceLoader family on 2026-08-02.

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

## Unargued NO_COVERAGE pass (2026-08-01)

Seven `iterator` `NO_COVERAGE` rows carried no argument anywhere in this file:
`readNumberOrNumberString` (4), `skipUntil` (2), `closeObj` (1). They were
`unlabeled`, and an unlabeled row is exempt from the family-label mention
warning, so nothing had ever surfaced them — the label state read as "settled
before labels existed" while the argument it pointed at did not exist.

None of them was an equivalence candidate. All three methods are covered
public API whose *branches* were unreached, which is the case the doctrine
calls mechanical work rather than acceptance:

- `readNumberOrNumberString` — every existing caller passed a **quoted**
  number, so only the `STRING` arm ever ran. The bare-number, `null`, and
  wrong-type arms had no coverage at all, which is why `return ""` for
  `readNumberAsString()` and `return ""` in place of `null` were both
  invisible. Killed by
  `TestFloat.test_read_number_or_number_string_across_value_types`, which
  pins all four arms including the error's op and the type it names.
- `skipUntil` — every existing test entered through `{` followed by a field,
  so the two other arms behind that brace never executed: an object that ends
  before any field (`{}` → "not found", not an error) and a brace followed by
  neither a field nor `}` (`{5}` → error, not a quiet "not found"). The two
  answers must not collapse into one. Killed by
  `TestSkip.test_skip_until_opening_brace_arms`, which also swept up the
  line-411 `EQUAL_IF` survivor.
- `closeObj` — the failure path was covered in `TestErrorReporting`; the
  success arm that matches `}` and hands the iterator back for chaining had
  no test, so returning `null` instead was indistinguishable. Killed by
  `TestObject.test_close_obj_returns_the_iterator`.

Result: `iterator` 1776→1787 of 1919 detected, baseline 143→132 rows,
`NO_COVERAGE` rows 21→14. `numbers` and `util` were unaffected (the new tests
are in `Test*` classes all three suites match, but they exercise no code those
suites mutate). Refresh took two passes for a line-less-key reason worth
remembering: the shrink-only prune dropped only 8 of the 11, because the
two dead `skipUntil` line-420 rows share `class,method,mutator` with live
survivors at 397/401 and prune keeps any coordinate still unkilled at another
status. A full baseline rewrite on a solo run finished the job after a
per-key diff of report against baseline confirmed the only mismatches were
those 3 rows — no accepted row was missing a counterpart to a timeout, so
there was no flip insurance to lose.

The remaining `NO_COVERAGE` debt was re-triaged the next day, below.

## NO_COVERAGE re-triage (2026-08-02)

The 18 rows deferred above, resolved in three different directions —
notably, *none* of them was what its recorded argument said it was:

- **The ServiceLoader family (6 rows) was killed, disproving the
  unreachable-in-harness acceptance.** The claim held for the module path but
  ignored that PIT minions always run on the class path, where a
  `META-INF/services` file in test resources *is* scanned.
  `TestParserFactoryLoading` registers two fixture factories (nested inside
  the `Test*` class so they stay out of the mutated population) and branches
  on a `ServiceLoader` probe: on the module path it asserts the no-factory
  error, on the class path it asserts prefix resolution, wrong-factory
  rejection, and the error message — killing all six mutants, the
  `NakedReceiverMutator` included. Deterministic in both environments, so it
  satisfies the never-commit-environment-dependent-results rule. This is the
  fleet's second disproven "the harness cannot reach this" (sava-build
  HARDENING.md dates the first 2026-07-26); the acceptance had named the
  missing capability as "a blackbox suite with its own module descriptor",
  which was one capability more than the kill actually needed.
- **The 12 trap rows were never traps** — see the Throw-terminated blocks
  family above for the mechanism (PIT block coverage cannot observe a block
  that always exits by throw). 10 rows stay `NO_COVERAGE` permanently with
  their contracts now asserted; 2 (`parseFieldEqualsSlow` 434) moved to the
  unreachable defense-in-depth family as dead defensive code.
- **The new contract tests killed 10 accepted SURVIVED siblings as a
  side-effect** — 5 `DoubleParser.parse` boundary/conditional mutants whose
  slow-path-routing arguments only held while no test asserted the
  reference-parser throw for `-`/`1e`/`1e±`/`1e±x` (a mutant routing those
  *away* from `slow` returned numbers where the contract demands the legacy
  exception), and 5 `parseFieldEquals` prefix-compare mutants with no
  truncation-shaped witness. Routing arguments are direction-specific: "more
  inputs to the oracle" is equivalent, "fewer" never was, and only the
  malformed inputs could tell the two apart.

Result: `iterator` 1787→1798 of 1919 (baseline 127→121 after the interleaved
prunes/updates; net 132→121 across the pass), `numbers` 250→255 of 326
(baseline 76→71), `NO_COVERAGE` rows 25→12, every remaining one argued under
a mechanism verified this pass. `qualityGate` green.

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

The baseline is otherwise fully triaged; no untriaged debt remains
(the `JsonIterParser` bufSize-shim family closed 2026-07-21 with the shim's
removal — 25.3.0 carried the `forRemoval` marker — and `TestJsonIterParser`
covering the surviving convenience overloads).

Shrinking the baseline is always an improvement; growing it requires a
reason here.
