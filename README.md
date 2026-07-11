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

Benchmarks cross-check that all implementations produce identical results during setup.
