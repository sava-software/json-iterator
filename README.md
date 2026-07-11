# JSON Iterator [![Gradle Check](https://github.com/sava-software/json-iterator/actions/workflows/build.yml/badge.svg)](https://github.com/sava-software/json-iterator/actions/workflows/build.yml)

JSON Iterator is a minimal adaption of the [stream parsing features](http://jsoniter.com/java-features.html#iterator-to-rescue) from the project [json-iterator/java](https://github.com/json-iterator/java).

Functionality has been extended with inversion-of-control mechanics to help minimize object creation.

Parsing supports String, byte[], char[] data sources.

## Basic Usage

See [JsonIterator.java](json-iterator/src/main/java/systems/comodal/jsoniter/JsonIterator.java) for the public interface.

```java
var jsonIterator = JsonIterator.parse("{\"hello\": \"world\"}");
System.out.println(jsonIterator.readObjField() + ' ' + jsonIterator.readString());
```

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

## Benchmarks

JMH benchmarks live in the standalone [`jmh/`](jmh) build, which includes this project as a composite build — they always
measure the current working tree. [simdjson-java](https://github.com/simdjson/simdjson-java) is compiled from a local
checkout for comparison; point `simdjsonJavaSrc` at its `src/main/java` directory if yours is somewhere other than the
default in [jmh/build.gradle.kts](jmh/build.gradle.kts). The JDK used is auto-detected via
`org.gradle.java.installations.paths` in [jmh/gradle.properties](jmh/gradle.properties); adjust it to your JDK location.

Run the full suite:

```shell
cd jmh
../gradlew jmh
```

Or build the JMH jar once and run selected benchmarks with JMH options (regex filter, profilers, iteration control):

```shell
../gradlew jmhJar
java --add-modules jdk.incubator.vector -Dorg.simdjson.species=256 \
  -jar build/libs/json-iterator-jmh-jmh.jar "SolanaBlockBench" -f 1 -wi 5 -w 1 -i 8 -r 1 -prof gc
```

`-Dorg.simdjson.species=256` is required on CPUs without 256-bit vectors (e.g. ARM NEON): simdjson-java only supports
256/512-bit species, so it runs emulated there — expect its numbers to be unrepresentative on such hardware.

Suites:

- [TwitterBench](jmh/src/jmh/java/systems/comodal/jsoniter/jmh/TwitterBench.java): the classic simdjson `twitter.json`
  corpus — full walk, selective extraction, and parse-only stage costs across `JsonIterator`, `IndexedJsonIterator`, and
  simdjson-java.
- [SolanaBlockBench](jmh/src/jmh/java/systems/comodal/jsoniter/jmh/SolanaBlockBench.java): a real Solana mainnet
  `getBlock` response (~4.7 MiB, stored gzipped in resources), including a sava-style inversion-of-control DTO parse.
- [SyntheticBench](jmh/src/jmh/java/systems/comodal/jsoniter/jmh/SyntheticBench.java): targeted workloads — large
  container skips, long strings, 19-digit longs, doubles, and RFC-1123/ISO timestamps.
- [Base64Bench](jmh/src/jmh/java/systems/comodal/jsoniter/jmh/Base64Bench.java): `decodeBase64String` strategies across
  payload sizes.
- [MinifyBench](jmh/src/jmh/java/systems/comodal/jsoniter/jmh/MinifyBench.java): `JIUtil.minify` versus a scalar
  baseline on pretty-printed and compact documents.

Benchmarks cross-check that all implementations produce identical results during setup. The published release
(currently `21.0.12`, shade-relocated so both versions share the classpath) is included as a baseline for the
`JsonIterator` workloads.

### Results

Apple M-series (128-bit NEON), JDK 27 EA, µs/op — lower is better. Relative results will differ on x86: NEON makes
vector-mask extraction expensive, which penalizes the scanning loops here and is cheap on AVX2/AVX-512.

| Workload | 21.0.12 | `JsonIterator` | `IndexedJsonIterator` |
|---|---:|---:|---:|
| twitter.json (617 KiB) full walk | 737 | 1,043 | 1,289 |
| twitter.json selective (screen names) | n/a¹ | 594 | **462** |
| Solana block (4.7 MiB) full walk | 7,996 | **6,505** | 9,082 |
| Solana block selective (fees) | **2,591**² | 4,100 | 3,146 |
| Solana block DTO parse (IOC) | **2,734**² | 4,217 | 3,532 |
| skip 1 MiB container | **112**² | 203 | 142 |
| long strings, `readString` | 30.6 | **14.9** | 52.8 |
| long strings, `applyChars` (IOC) | 147.5 | **14.8** | — |
| 19-digit longs | **47.5** | 53.4 | 71.0 |
| doubles | 107.9 | **52.4** | 65.0 |
| RFC-1123 timestamps | 137.7 | **53.8** | — |
| ISO-8601 timestamps | 129.6 | **73.3** | — |
| base64, 256 KiB value (bytes) | 89.6 | **50.8** | — |
| base64, 256 KiB value (chars) | 188.7 | **116.5** | — |
| minify 4.7 MiB (vs 3,392 scalar) | — | **1,895** | — |

¹ 21.0.12 cannot parse twitter.json: its word-at-a-time scanning desyncs on multi-byte content (fixed after 21.0.12).
That bug is also why its string skipping is fast — it hops 8-byte words through strings with almost no per-byte checks.

² Skip-dominated workloads on ASCII-heavy documents. On this NEON hardware the vectorized scans pay mask-extraction
costs that the old 8-byte SWAR loops avoid, so 21.0.12 leads where skipping dominates; value decoding (strings,
numbers, dates, base64) is where the current version wins, roughly 2× across the board. `parseOnly` stage-1 indexing
for the 4.7 MiB block is 2,400 (2,504 with UTF-8 validation).
