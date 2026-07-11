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
- [MinifyBench](jmh/src/jmh/java/systems/comodal/jsoniter/jmh/MinifyBench.java): `JIUtil.minify` versus a scalar
  baseline on pretty-printed and compact documents.

Benchmarks cross-check that all implementations produce identical results during setup. The published release
(currently `21.1.0`, shade-relocated so both versions share the classpath) is included as a baseline for the
`JsonIterator` workloads.

### Results

Apple M-series (128-bit NEON), JDK 27 EA, µs/op — lower is better. `21.1.0` is the published scalar (Java 21)
release; `JsonIterator`/`IndexedJsonIterator` are this branch, including the hybrid SWAR-prefix string scans.
simdjson-java is omitted: it requires 256/512-bit species and runs emulated (~100-200× slower) on this hardware.

| Workload | 21.1.0 | `JsonIterator` | `IndexedJsonIterator` |
|---|---:|---:|---:|
| twitter.json (617 KiB) full walk | **516** | 1,073 | 1,272 |
| twitter.json selective (screen names) | **406** | 493 | 456 |
| Solana block (4.7 MiB) full walk | 5,773 | **5,498** | 7,980 |
| Solana block selective (fees) | **2,339** | 3,720 | 3,091 |
| Solana block DTO parse (IOC) | **2,446** | 4,169 | 3,365 |
| skip 1 MiB container | **101** | 194 | 144 |
| long strings, `readString` | 29.0 | **17.5** | 55.7 |
| long strings, `applyChars` (IOC) | 38.0 | **18.5** | — |
| 19-digit longs | **46.0** | 52.8 | 71.5 |
| doubles | 52.1 | 52.4 | 64.2 |
| RFC-1123 timestamps | 66.8 | 70.9 | — |
| ISO-8601 timestamps | **77.4** | 89.4 | — |
| minify 4.7 MiB (vs 3,503 scalar) | — | **1,847** | — |

On this 128-bit NEON hardware the scalar 21.1.0 release leads wherever short strings or skipping dominate: the
vector scan loops pay an expensive mask-extraction on every hit, which real documents trigger constantly, while
21.1.0's 8-byte SWAR loops make hits nearly free. This branch wins on long uniform runs (long strings, minify),
where wide chunks amortize. The balance is expected to shift on AVX2/AVX-512, where mask extraction is a single
instruction — treat these numbers as one point on that curve, not a verdict. `parseOnly` stage-1 indexing for the
4.7 MiB block is 2,357 (2,480 with UTF-8 validation) and 275 for twitter.json.
