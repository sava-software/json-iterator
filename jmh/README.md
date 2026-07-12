# JMH Benchmarks

Benchmarks for the inversion-of-control (IOC) parsing APIs: the char-buffer
`fieldEquals` chains versus the `FieldMatcher` / `matchString` dispatch added
on `feat/new-ioc`.

## Running

This directory is a standalone Gradle build that includes the library build
from `..`, so benchmarks always run against the local sources.

```sh
cd jmh
../gradlew jmh          # run everything with the defaults in build.gradle.kts
```

For ad-hoc runs (subsets, fork counts, profilers), build the fat jar once and
drive JMH directly — the `me.champeau.jmh` plugin does not forward `-P`
options to JMH:

```sh
../gradlew jmhJar
java -jar build/libs/json-iterator-jmh-jmh.jar 'MigrationBench' -f 3 -wi 5 -w 1s -i 6 -r 1s -foe true
```

The first argument is a regex over benchmark names (e.g. `'dispatchTwitter'`,
`'blockParse_matcher$'`).

The JDK used by Gradle is pinned in `gradle.properties`
(`org.gradle.java.installations.paths`); adjust it to a local JDK 27 install.

**Always compare with at least `-f 3`.** Single-fork results on this codebase
swing 10–20% on JIT inlining luck alone — a real 21% win has measured as a
13% loss in one fork. Every benchmark's `@Setup` cross-checks that all
variants of a workload produce identical checksums, and `-foe true` turns any
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

## Migration decision table

Measured 2026-07-12 on an Apple Silicon macOS box, JDK 27 EA, 3 forks; treat
the ratios as the signal, not the absolute numbers. "Before" is the pattern as
it exists in sava / idl-src-gen today; "after" is the FieldMatcher migration.
These numbers postdate the `FieldMatcher` hash moving to `VarHandle` word
loads — with the earlier byte-at-a-time hash the matcher lost several of
these comparisons, so re-verify against library changes before re-deciding.

| Pattern (survey → benchmark) | Before µs/op | After µs/op | Verdict |
|---|---|---|---|
| Large value union, 37–52 branches (`IxError`, `TransactionError`) → `dispatchTwitter` | 1194 (linear char chain) | 655 (matcher) | **Migrate — biggest win (~45%)** |
| Kind dispatch, 30 names (`TypeNode`/`ValueNode`) → `kindDispatch` | 588 (`readString` + String switch) | 537 (`matchString`) | **Migrate — ~9% and zero allocation** |
| Wide DTO with real value reads, 13 fields (`TxMeta`) → `txMetaWalk` | 2847 (char chain) | 2759 (matcher) | **Migrate — ~3%**; value consumption dominates, win is mostly code shape |
| Small enum, 3 names (`Commitment`, `RpcEncoding`) → `enumValues` | 432 (char chain) | 505 (`matchString`) / 792 (`applyChars` + `match`) | **Keep the chain** — first-compare hits beat any hash below ~8–10 names |
| Selective parse early-out → `blockParse_matcherMasked` | 2587 (matcher) | 2510 (masked) | **~3%** — worth it only where the wanted set is small and objects are large |
| `readObjField()` String loops (idl-src-gen) → `fieldWalkTwitter` | 568 | 543 (char IOC) | **~5%** — migrate for allocation hygiene, not speed |

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
