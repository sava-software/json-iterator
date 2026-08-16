# JSON Parsing Test Suite

An external accept/reject oracle over RFC 8259, driven by `TestJsonTestSuite`.
Everything else that checks parsing here is *self*-differential — the fuzz
targets compare the byte source against the char source, which cannot see a bug
both sources share. This corpus is the axis those cannot cover: a third party's
opinion about which documents are JSON.

Keep prose out of `test_parsing/`, the same rule the fuzz corpora follow: this
README and `expected.tsv` sit beside it because the test asserts that the
directory and the table hold exactly the same names.

## Provenance

- Upstream: <https://github.com/nst/JSONTestSuite>, commit `1ef36fa0` (2024-11-22).
- `test_parsing/` is a byte-identical copy of upstream's directory of the same
  name: 318 files, 95 `y_` (must parse), 188 `n_` (must be rejected), 35 `i_`
  (implementation-defined). `TestJsonTestSuite` pins all three counts, so
  re-vendoring a moved corpus fails rather than silently widening the claim.
- MIT licensed, Copyright (c) 2016 Nicolas Seriot. The upstream `LICENSE` text
  is not duplicated here; the copy is unmodified and attributed above.
- Upstream's `test_transform/` is **not** vendored. Those cases judge the *text
  a parser re-emits* for ambiguous inputs, and this library has no serializer to
  ask — `JIUtil.escapeJson` escapes a string, it does not render a document.
- JEP 540's Simple JSON API commits to testing against this same suite, so when
  `jdk.incubator.json` becomes available as a differential oracle it will already
  be measured against the bar recorded here.

To re-vendor: replace `test_parsing/`, run the suite, and re-argue every row
whose verdict moved. The counts above are the tripwire.

## What the driver is

The suite asks whether a *document* is valid. This library is a pull parser and
has no whole-document entry point — the caller decides when it has read enough —
so `TestJsonTestSuite.walk` supplies the missing layer: read exactly one value,
decoding every scalar along the way, then require the remainder of the input to
be whitespace. The conformance claim below is therefore about *the library plus
that ten-line driver*, which is the honest scope, and rejections record which of
the two produced them:

| verdict | meaning |
| --- | --- |
| `accept` | one complete value, nothing but whitespace after it |
| `reject` | the library threw `JsonException` |
| `reject-at-eof` | the library read a complete value; the trailing input is what made the document invalid |

18 rows are `reject-at-eof`. They are correct rejections, but attributing them to
the driver rather than to the library is the point of splitting the two: `[][]`
and `{"a":1} trailing` are documents whose *prefix* this library accepts, and a
caller that stops reading when its parse is done will never notice the rest.

Both sources are checked on every case. 25 of the 318 files are not well-formed
UTF-8, so only the byte source sees those; where the char source runs, it has
never disagreed with the byte source on this corpus.

## Result

**No valid document is rejected** — all 95 `y_` cases parse, on both sources.
That half of the result is asserted as a total (`test_no_valid_document_is_rejected`)
rather than left implicit in 95 rows, because it is a flat claim with nothing
negotiated in it.

13 of the 188 `n_` cases are accepted, in two families and no others
(`test_leniency_is_confined_to_the_argued_families` pins the count so a new
leniency cannot arrive by joining an existing family).

### `lenient-number-grammar` — 10 cases

Leading zeros (`[012]`, `[-01]`, `[-012]`) and a missing integer or fraction part
(`[1.]`, `[-2.]`, `[-.123]`, `[0.e1]`, `[2.e3]`, `[2.e+3]`, `[2.e-3]`).

Every one is a string `Double.parseDouble` accepts, which is the whole of the
family: `DoubleParser`'s class javadoc already records that inputs outside its
fast grammar — "`Infinity`/`NaN`, leading `+` or `.`, hex floats, `f`/`d`
suffixes, surrounding whitespace" — defer to `Double.parseDouble`, whose grammar
is a superset of RFC 8259's, and the fast path does not reject a leading zero
either. So this family is pre-existing and written down, not discovered here.

**Ratified 2026-08-16**: `readDouble("012") == 12.0` is the behaviour this
library intends to keep. Recording that matters because the rest of this file
argues that a table row is not an argument — this one is decided, not inherited
from a javadoc that happened to describe it.

**Read numbers with `readDouble`, not `readNumberAsString`.** The first driver
written here scanned tokens instead of parsing them and reported 26 cases in this
family rather than 10. A token scan is not a parse: `readNumberAsString` hands
back `"1+2"`, `"0e"`, `"0e+-1"` and `"-"` whole, and only something that parses
them rejects. Those 16 were the driver's leniency being credited to the library.
`readDouble` is also the strictest number read that is valid for *every* JSON
number — `readBigInteger` rejects `1.0`, and `readLong` rejects `1e999`, both of
which are valid documents — so it is the strictest choice available, not a
convenient one.

The remaining leniency does not extend to *values*: on these ten tokens
`readDouble` returns what `Double.parseDouble` would.
`readBigDecimal`/`readBigInteger` may instead throw `NumberFormatException`,
which `NumberFuzz` already records as an accepted rejection for those two
readers.

### `lenient-unescaped-control` — 3 cases

`["a<NUL>a"]`, `["new<LF>line"]`, `["<TAB>"]`.

RFC 8259 §7 requires U+0000–U+001F inside a string to be escaped. The string
scan here stops at `"` and `\`, so a raw control character passes through into
the decoded `String`.

Unlike the number family this one has **no prior written decision** behind it —
it is recorded as measured behaviour, not ratified as intended, and this note is
the marker that the row is unfinished business rather than an argument that it is
correct.

It is not fixed here because the fix is not free and not local: rejecting a raw
control character means a per-character test inside the string scan, which is the
hottest loop in the library and the one every silent-corruption bug on record has
lived in. This repo settles that kind of trade by measuring it (`EscapeBench`'s
lookup table was measured and rejected on exactly this shape of argument), and a
`byte[]`-source consumer pays the cost on every string it reads while the benefit
is rejecting input that no surveyed consumer produces. That is a maintainer's
decision with a benchmark attached, not something an added test may settle by
writing the current behaviour into a table.

## Implementation-defined cases

The 35 `i_` rows carry the choice this library makes, by family. 15 are accepted.

| family | rows | what this library does |
| --- | --- | --- |
| `number-out-of-range` | 10 | accepts. Huge exponents, overflow and underflow are token-level valid; whether the value is representable is the typed reader's business, not the parser's |
| `escaped-surrogate` | 10 | mixed, 4 accepted. `\u` escapes are validated as a pair, so an inverted or truncated pair is rejected, while a lone escaped surrogate that forms a well-formed UTF-16 unit is not. Distinct from `raw-utf8-invalid`: a surrogate *encoded in UTF-8* (`ED A0 80`) is rejected, because that is a byte sequence UTF-8 has no spelling for, whereas `\uD800` is a UTF-16 code unit the escape syntax can name |
| `raw-utf8-invalid` | 10 | rejects, all ten. The byte source's decoder is held to the JDK's strict decoder by `TestMultiByteScanEdges.test_utf8_acceptance_matches_the_jdk_decoder`, which sweeps the whole 2-byte space and every 3-byte lead rather than a hand-picked list |
| `utf16-not-utf8` | 3 | rejects. UTF-16 input is not UTF-8 input |
| `byte-order-mark` | 1 | rejects. A BOM is not whitespace and no source strips one |
| `deep-nesting` | 1 | accepts 500 nested arrays. There is no depth limit; the walk is iterative on both sides, and the corpus's 100,000- and 50,000-deep cases are rejected for running out of input, not for depth |

## Neither scanning path is this strict

Two of this library's paths deliberately do less than the decoding walk, and both
gaps are recorded as measured numbers rather than as table rows — writing them
into `expected.tsv` would promote a scan's leniency to a specification one row at
a time.

`readNumberAsString` returns the token text without parsing it, and accepts **16
documents this driver rejects** (`[1+2]`, `[0e]`, `[-]`, `[0.1.2]` and relatives).
A caller that reads numbers as strings and parses them itself inherits that gap
and its own parser's verdict.

## The skip path is not this strict either

`skip()` is a structural scan that does not decode what it passes, and on this
corpus it accepts **78 documents that the decoding walk rejects** — invalid
numbers, truncated literals, unquoted keys, `[1,,2]`. That is the design, and the
test asserts only the direction that must hold: nothing the decoding walk accepts
may be rejected by `skip()` (measured: 0 cases). What it deliberately does *not*
do is write those 78 into the table, which would promote the scan's leniency to a
specification one row at a time.

The number is here because it is the size of the gap between "this library
parsed it" and "this library skipped it", and a caller who skips subtrees is
living on the second number.
