# JSON Iterator [![Gradle Check](https://github.com/sava-software/json-iterator/actions/workflows/build.yml/badge.svg)](https://github.com/sava-software/json-iterator/actions/workflows/build.yml)

A streaming JSON parser for Java that reads documents without materializing them.

Instead of building a tree or binding to a DTO, the iterator walks the document and hands each
field name and value to your code as a span of the source buffer. Nothing is allocated on the way
in — you construct only the objects you actually want, directly from the bytes.

```java
var jsonIterator = JsonIterator.parse("{\"hello\": \"world\"}");
System.out.println(jsonIterator.applyObject(
    (buf, offset, len, ji) -> new String(buf, offset, len) + ' ' + ji.readString()
));
```

- **Inversion of control.** `testObject`/`applyObject` push field spans into functional interfaces
  (`FieldBufferPredicate`, `ContextCharBufferFunction`, …) so a parse can complete with zero
  intermediate `String`s. Context-carrying variants let handlers stay static and capture nothing.
- **O(1) field dispatch.** [`FieldMatcher`](json-iterator/src/main/java/systems/comodal/jsoniter/FieldMatcher.java)
  compiles expected field names into a hash table once, then maps each name to its declared index
  for a `switch` — flat cost regardless of how wide the union gets.
- **Purpose-built value parsers.** Fast paths for doubles, longs, `Instant`, hex, and base64 that
  read from the buffer rather than from an intermediate string.
- **Four sources.** `byte[]`, `char[]`, `String`, and `InputStream`. Feeding `byte[]` off the wire
  is the fast path; see [jmh/README.md](jmh/README.md) for the measured trade-offs.


## Usage

See [JsonIterator.java](json-iterator/src/main/java/systems/comodal/jsoniter/JsonIterator.java) for
the public interface.

## Build

[Generate a classic token](https://github.com/settings/tokens) with the `read:packages` scope needed to access
dependencies hosted on GitHub Package Repository.

#### ~/.gradle/gradle.properties

```gradle.properties
savaGithubPackagesUsername=GITHUB_USERNAME
savaGithubPackagesPassword=GITHUB_TOKEN
```

```shell
./gradlew check
```

## Credits

Originally adapted from the [stream parsing features](http://jsoniter.com/java-features.html#iterator-to-rescue)
of [json-iterator/java](https://github.com/json-iterator/java). The parsing engine, dispatch APIs,
and value parsers have since been rewritten.
