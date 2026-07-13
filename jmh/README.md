# JMH Benchmarks

Benchmarks for the inversion-of-control (IOC) parsing APIs: the char-buffer
`fieldEquals` chains versus the `FieldMatcher` / `matchString` dispatch added
on `feat/new-ioc`.

## Running

This directory is a standalone Gradle build that includes the library build
from `..`, so benchmarks always run against the local sources.

```sh
cd jmh
../gradlew jmh                                    # everything, quick-look defaults (1 fork)
../gradlew jmh -PjmhFork=3 -PjmhIncludes=enumValues,kindDispatch   # decision-grade subset
```

Configuration comes from the shared `software.sava.build.feature.jmh`
convention plugin (resolved from the sibling `../sava-build` checkout). Every
default it sets is overridable per invocation: `-PjmhFork`, `-PjmhIncludes`,
`-PjmhWarmupIterations`, `-PjmhWarmup`, `-PjmhIterations`,
`-PjmhTimeOnIteration`, `-PjmhFailOnError`, and
`-PjmhJvmArgsAppend="<flag> <flag>..."` — the last replacing the service
flag set wholesale (e.g. to A/B the GC). For anything the extension does not
expose (profilers, output formats), build the fat jar once and drive JMH
directly. Each Gradle run's raw output is archived timestamped under
`jmh-results/` (in the project directory, outside `build/`, so `clean`
cannot erase measurement history), and `build/results/jmh/results.txt` is
re-rendered after every run as the merge of all archived runs (newest row
wins per benchmark) — so subset runs converge on a full scoreboard. The
archive is the source of truth: delete an archive file to drop a bad run's
rows from the next merge, and mind that rows can be of mixed vintage.

```sh
../gradlew jmhJar
java -XX:+UseCompactObjectHeaders -Xms2g -Xmx2g -XX:+AlwaysPreTouch \
  -XX:+PerfDisableSharedMem -XX:+UseZGC \
  -jar build/libs/json-iterator-jmh-jmh.jar 'MigrationBench' -f 3 -wi 5 -w 1s -i 6 -r 1s -foe true
```

The Gradle `jmh` task passes this flag set to the forked benchmark JVMs by
default — compact object headers (product since JDK 25), generational ZGC
(what the consuming sava services run), a pinned pre-touched heap, and
`-XX:+PerfDisableSharedMem` — to replicate the long-running-service JVM the
library targets. Direct jar runs must pass the flags themselves as above;
JMH forks inherit the parent JVM's flags, so placing them before `-jar`
covers the forked benchmark JVMs too.

The first argument is a regex over benchmark names (e.g. `'dispatchTwitter'`,
`'blockParse_matcher$'`).

The JDK used by Gradle is pinned in `gradle.properties`
(`org.gradle.java.installations.paths`); adjust it to a local JDK 27 install.

**Always compare with at least `-f 3`, and run in isolation.** Single-fork
results on this codebase swing 10–20% on JIT inlining luck alone — a real 21%
win has measured as a 13% loss in one fork. Concurrent builds or other
benchmark runs on the same machine inflate error bars enough to flip
close verdicts (a ±134 error that was really ±8 in isolation has happened);
treat any row with an error above ~10% of its score as contaminated and
re-run it alone. Every benchmark's `@Setup` cross-checks that all variants of
a workload produce identical checksums, and `-foe true` turns any
disagreement into a hard failure.

## Benchmarks

- **`IocBench`** — primitive comparisons over two real documents (a ~4.7 MiB
  Solana `getBlock` response, gzipped in resources, and a ~600 KiB twitter
  search response): field-name delivery (`fieldWalk*`: char IOC vs the
  deprecated `readObjField`), dispatch at 94 field names (`dispatchTwitter*`:
  linear char chain vs `FieldMatcher`, byte and char inputs), string-value
  dispatch (`valueDispatch*`: `matchString` vs `applyChars` +
  `FieldMatcher.match`), and a sava-style selective DTO parse (`blockParse*`:
  chain vs matcher vs masked early-out).
- **`MigrationBench`** — end-to-end before/after models of the real parser
  shapes found in `sava` and `idl-src-gen`, including realistic value
  consumption that dilutes pure dispatch wins: `txMetaWalk` (13-field TxMeta
  chain), `enumValues` (3-name Commitment-style enum), and `kindDispatch`
  (30-name Codama `"kind"` discriminator).
- **`SourceBench`** — prices the four data sources `JsonIterator` accepts
  (`byte[]`, `String`, `char[]`, `InputStream`) over one identical field-name
  walk, so each pairwise delta isolates a single cost: iterator allocation, the
  `getBytes()` encode, the char path, and the stream copy.

## Data source decision table

Measured 2026-07-13 (`-PjmhFork=3`, 24 samples, same machine and flag set as
below) over the same two documents. **Feed `byte[]`.** The deltas below are all
against `bytes_reset` — one iterator, `reset(byte[])` per document.

The two documents differ in the way that turns out to matter most: twitter is
631 KiB and **15.1% non-ASCII**, so its `String` is UTF-16-backed; solana is
4.7 MiB and **pure ASCII**, so its `String` is Latin-1 compact.

| Source | twitter µs/op | solana µs/op | vs `byte[]` | Verdict |
|---|---|---|---|---|
| `bytes_reset` — reuse one iterator | 450 ± 5 | 3923 ± 77 | — | **The shape to write.** |
| `bytes_parse` — fresh iterator per document | 448 ± 3 | 3917 ± 55 | 1.00× / 1.00× | **Iterator reuse buys nothing** at document scale — allocation amortizes to noise across a 600 KiB+ parse (a dead tie on both documents, across two runs). `reset()` only matters for high rates of *small* documents |
| `string_parse` — `parse(String)` | 1139 ± 34 | 5225 ± 180 | **2.53× / 1.33×** | **Never feed a `String`.** See below |
| `string_toChars` — `parse(str.toCharArray())` | **560 ± 21** | 5600 ± 268 | 1.24× / 1.43× | The *other* road out of a String, and on UTF-16 content it beats `parse(String)` by **2.03×**. See below |
| `chars_reset` — reuse one chars iterator | 495 ± 6 | 4699 ± 302 | 1.10× / 1.20× | The char-path tax: 16-bit words scan half the payload per load. Real, but an order of magnitude smaller than the String tax |
| `stream_reset` — `reset(InputStream)` | 526 ± 96 ⚠️ | 4296 ± 181 | 1.17× / 1.10× | Roughly a 10–20% tax, but **GC-sensitive and unstable across runs** (the previous run had twitter free and solana at +19%): `readAllBytes()` allocates a full-document copy per call. There is **no incremental parse** — the stream is read to EOF and the resulting array is iterated |

### The String tax is an encode, not a copy — and which conversion is expensive depends on the String

`parse(String)` routes through `String.getBytes()`. When the String is
UTF-16-backed — which any non-ASCII content forces — that is a full UTF-8
*encode*, not a memcpy. Subtracting the walk from each row isolates the two
conversions, and they invert between the documents:

| conversion | twitter (UTF-16 String) | solana (Latin-1 String) |
|---|---|---|
| `getBytes()` — `string_parse` − `bytes_parse` | **691 µs** (UTF-8 encode) | 1308 µs (near-memcpy) |
| `toCharArray()` — `string_toChars` − `chars_reset` | **65 µs** (straight copy) | 901 µs (Latin-1 → UTF-16 inflate) |

Each String pays whichever conversion runs against the grain of its own backing.
A UTF-16 String yields chars almost free and must transcode to yield bytes; a
Latin-1 String yields bytes almost free and must inflate to yield chars.

Two rules follow:

1. **Don't create the `String` at all.** Parse the bytes as they came off the
   wire. `parse(String)` is a convenience for tests and the REPL, not a hot path.
2. **If you are already holding a `String`, feed `toCharArray()` — unless you
   know the content is pure ASCII.** On UTF-16 content the char path wins 2.03×
   (560 vs 1139 µs) because the 65 µs copy plus the char path's 10% scanning tax
   is nowhere near the 691 µs encode. On pure-ASCII content `getBytes()` is
   better by ~7% (5225 vs 5600 µs, overlapping intervals — call it a wash), since
   there the inflate is the expensive direction. **Do not repeat "narrow to bytes
   once" as general advice**: it holds only when the bytes never became a String.

## Migration decision table

Measured 2026-07-12 on an Apple Silicon macOS box: one isolated full-suite
run (`../gradlew jmh -PjmhFork=3`, 24 samples per benchmark), JDK 27 EA (the
library targets 25), under the default service flag set above (ZGC, compact
headers, pinned pre-touched heap); treat the ratios as the signal, not the
absolute numbers. "Before" is the pattern as it exists in sava / idl-src-gen
today; "after" is the FieldMatcher migration. Two history notes: these
numbers postdate the `FieldMatcher` hash moving to `VarHandle` word loads
(the earlier byte-at-a-time hash lost several comparisons), and the earlier
G1 baseline produced the same dispatch verdicts but larger margins on the
small-win rows — the big wins are GC-robust, the ~3–5% wins are not.

| Pattern (survey → benchmark) | Before µs/op | After µs/op | Verdict |
|---|---|---|---|
| Large value union, 37–52 branches (`IxError`, `TransactionError`) → `dispatchTwitter` | 1074 ± 20 (linear char chain) | 646 ± 22 (matcher) | **Migrate — biggest win (~40%; ~33–45% across GC/runs)** |
| Kind dispatch, 30 names (`TypeNode`/`ValueNode`) → `kindDispatch` | 571 ± 16 (`readString` + String switch) | 512 ± 4 (`matchString`) | **Migrate — ~10% and zero allocation** |
| String-value dispatch plumbing → `valueDispatchTwitter` | 881 ± 19 (`applyChars` + `match`) | 806 ± 23 (`matchString`) | **Prefer `matchString` (~8%)** where the unrecognized text isn't needed; `applyChars` + `match` remains the fallback-preserving form |
| Wide DTO with real value reads, 13 fields (`TxMeta`) → `txMetaWalk` | 2856 ± 49 (char chain) | 2838 ± 80 (matcher) | **Migrate for code shape** — a wash to ~3% depending on GC/run; value consumption dominates |
| Small enum, 3 names (`Commitment`, `RpcEncoding`) → `enumValues` | 468 ± 12 (char chain) | 481 ± 5 (`matchString`) / 748 ± 8 (`applyChars` + `match`) | **Keep the chain — a ~3% edge with overlapping CIs, effectively a tie.** No performance reason to migrate tiny enums, and no penalty if one is migrated for consistency. `applyChars` + `match` stays clearly worst at this size |
| Selective parse early-out → `blockParse_matcherMasked` | 2544 ± 27 (matcher) | 2537 ± 65 (masked) | **Within error this run; ≤3% at best** — worth it only where the wanted set is small and objects are large |
| `readObjField()` String loops (idl-src-gen) → `fieldWalkTwitter` | 555 ± 46 | 605 ± 43 (char IOC) | **GC-sensitive: under ZGC + compact headers the String-per-field loop is ~8–12% *faster* than the char IOC walk** (it was ~5% slower under G1). The deprecation stands on API-consistency grounds, not performance |

Deprecations driven by this table (`@Deprecated(forRemoval = true)` in
`JsonIterator`): `testObject(C, ContextFieldBufferMaskedPredicate)` (unused by
known consumers; superseded by the index-masked matcher variant),
`readObject`/`readObjField`, and the `testObjField`/`applyObjField*` family.
The char `fieldEquals` chain API is deliberately **not** deprecated — it
remains the fastest dispatch for small field/value sets. The never-released
byte-chain surface (`testObjectBytes` + `FieldBytesPredicate` family) was
removed outright before first release: end-to-end it tied the char chain and
lost to the matcher, which reuses its zero-copy plumbing internally.

## Migration examples

Concrete before/after shapes for each row of the table and each deprecated
method, modeled on the real call sites in sava and idl-src-gen.

### Value unions — chain in a `CharBufferFunction` → `FieldMatcher.match`

The call site (`ji.applyChars(PARSER)`) is unchanged; only the function body
migrates, which preserves span access for the unknown-value fallback:

```java
// before — sava's TransactionError.PARSER, 37 branches
static final CharBufferFunction<TransactionError> PARSER = (buf, offset, len) -> {
  if (fieldEquals("AccountInUse", buf, offset, len)) {
    return AccountInUse.INSTANCE;
  } else if (fieldEquals("AccountLoadedTwice", buf, offset, len)) {
    return AccountLoadedTwice.INSTANCE;
  } // ... 34 more branches ...
  else {
    return new Unknown(new String(buf, offset, len));
  }
};

// after — one O(1) lookup, variants indexed in declaration order
private static final FieldMatcher ERRORS = FieldMatcher.of("AccountInUse", "AccountLoadedTwice" /* , ... */);
private static final TransactionError[] VARIANTS = {AccountInUse.INSTANCE, AccountLoadedTwice.INSTANCE /* , ... */};

static final CharBufferFunction<TransactionError> PARSER = (buf, offset, len) -> {
  final int i = ERRORS.match(buf, offset, len);
  return i < 0 ? new Unknown(new String(buf, offset, len)) : VARIANTS[i];
};
```

### Kind / discriminator dispatch — `readString()` + String switch → `matchString`

```java
// before — idl-src-gen's Codama node dispatch: allocates a String per node
return switch (ji.skipUntil("kind").readString()) {
  case "numberTypeNode" -> NumberTypeNode.parse(ji);
  case "structTypeNode" -> StructTypeNode.parse(ji);
  // ... ~28 more ...
  default -> throw new IllegalStateException("Unhandled kind.");
};

// after — allocation-free int switch; -1 covers unknown values and JSON null
private static final FieldMatcher KINDS = FieldMatcher.of("numberTypeNode", "structTypeNode" /* , ... */);

ji.skipUntil("kind");
return switch (ji.matchString(KINDS)) {
  case 0 -> NumberTypeNode.parse(ji);
  case 1 -> StructTypeNode.parse(ji);
  default -> throw new IllegalStateException(ji.currentBuffer());
};
```

`matchString` consumes the value, so callers that must report or wrap the
unrecognized text should stay on `applyChars` + `FieldMatcher.match` (previous
example) instead.

### Wide DTO field dispatch — `FieldBufferPredicate` chain → matcher `testObject`

```java
// before — sava's TxMeta-style parser, 13 branches
private static final ContextFieldBufferPredicate<Parser> META_PARSER = (p, buf, offset, len, ji) -> {
  if (fieldEquals("fee", buf, offset, len)) {
    p.fee = ji.readLong();
  } else if (fieldEquals("preBalances", buf, offset, len)) {
    p.preBalances = parseLamportBalances(ji);
  } // ... 11 more ...
  else {
    ji.skip();
  }
  return true;
};
ji.testObject(parser, META_PARSER);

// after — indices follow the matcher's declaration order
private static final FieldMatcher FIELDS = FieldMatcher.of("fee", "preBalances" /* , ... */);

private static final ContextFieldIndexPredicate<Parser> META_PARSER = (p, fieldIndex, ji) -> {
  switch (fieldIndex) {
    case 0 -> p.fee = ji.readLong();
    case 1 -> p.preBalances = parseLamportBalances(ji);
    // ...
    default -> ji.skip(); // unknown fields; strict parsers throw here instead
  }
  return true;
};
ji.testObject(parser, FIELDS, META_PARSER);
```

Aliased names (idl-src-gen accepts `"type"` or `"kind"` for the same field)
need no special support: declare both names and share a multi-label
`case 6, 7 ->`.

### Inherited field sets — `super.test(...)` chains → `FieldMatcher.of(base, ...)`

idl-src-gen splits one object's fields across a class hierarchy
(`BaseParser` ← `BaseDocsParser` ← concrete), each level re-running its own
chain before delegating up. The extending factory preserves base indices, so
the subclass `switch` delegates unhandled indices without remapping:

```java
// BaseParser
static final FieldMatcher BASE_FIELDS = FieldMatcher.of("name", "kind", "idlName", "events");

// concrete parser: base names keep indices 0-3, additions follow
static final FieldMatcher FIELDS = FieldMatcher.of(BASE_FIELDS, "docs", "type", "defaultValue");

(p, fieldIndex, ji) -> switch (fieldIndex) {
  case 4 -> { p.docs = parseDocs(ji); yield true; }
  case 5 -> { p.type = parseType(ji); yield true; }
  case 6 -> { p.defaultValue = parseValue(ji); yield true; }
  default -> BASE_PARSER.test(p, fieldIndex, ji); // 0-3 and unknown (-1)
};
```

### Small enums — keep the chain

Do not migrate 2–3-name value matchers like sava's `Commitment`; the linear
chain is the fastest option below roughly 8–10 names:

```java
// keep as-is — beats both matchString and match() at this size
public static final CharBufferFunction<Commitment> PARSER = (buf, offset, len) -> {
  if (fieldEquals("processed", buf, offset, len)) {
    return PROCESSED;
  } else if (fieldEquals("confirmed", buf, offset, len)) {
    return CONFIRMED;
  } else if (fieldEquals("finalized", buf, offset, len)) {
    return FINALIZED;
  } else {
    return null;
  }
};
```

### Deprecated: `testObject(C, ContextFieldBufferMaskedPredicate)` → index-masked matcher

Also the pattern for early-out on large objects (the `blockParse_matcherMasked`
benchmark). Note two sharp edges: `testObject` returns identically on
break-out and normal completion, so record the break-out in the context; and a
break-out leaves the iterator inside the object, so the caller must
`skipRestOfObject()`:

```java
private static final FieldMatcher FIELDS = FieldMatcher.of("fee", "computeUnitsConsumed");

private static final ContextFieldIndexMaskedPredicate<Parser> PARSER = (p, mask, fieldIndex, ji) -> {
  switch (fieldIndex) {
    case 0 -> { p.fee = ji.readLong(); mask |= 1; }
    case 1 -> { p.computeUnits = ji.readLong(); mask |= 2; }
    default -> ji.skip();
  }
  if (mask == 3) {
    p.complete = true;
    return ContextFieldIndexMaskedPredicate.BREAK_OUT;
  }
  return mask;
};

ji.testObject(parser, FIELDS, PARSER);
if (parser.complete) {
  ji.skipRestOfObject();
}
```

### Deprecated: `readObjField()` / `readObject()` loops → matcher `testObject`

```java
// before — allocates a String per field name (idl-src-gen's converter loops)
for (var field = ji.readObjField(); field != null; field = ji.readObjField()) {
  switch (field) {
    case "name" -> name = ji.readString();
    case "docs" -> docs = parseDocs(ji);
    default -> ji.skip();
  }
}

// after
private static final FieldMatcher FIELDS = FieldMatcher.of("name", "docs");

ji.testObject(this, FIELDS, (p, fieldIndex, ji2) -> {
  switch (fieldIndex) {
    case 0 -> p.name = ji2.readString();
    case 1 -> p.docs = parseDocs(ji2);
    default -> ji2.skip();
  }
  return true;
});
```

### Deprecated: `testObjField` / `applyObjField*` single-field probes → one-name matcher

Returning `false` breaks out with the iterator positioned at the just-matched
field's value, mirroring the probe semantics:

```java
// before — idl-src-gen's TypeNode discriminator probe
if (ji.testObjField((buf, offset, len) -> fieldEquals("kind", buf, offset, len))) {
  kind = ji.readString();
}

// after
private static final FieldMatcher KIND_FIELD = FieldMatcher.of("kind");

final String[] kind = {null};
ji.testObject(KIND_FIELD, (fieldIndex, ji2) -> {
  if (fieldIndex == 0) {
    kind[0] = ji2.readString();
    return false; // break out; remaining fields are the caller's to skip
  }
  ji2.skip();
  return true;
});
```
