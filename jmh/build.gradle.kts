plugins {
  java
  id("me.champeau.jmh") version "0.7.3"
}

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(27)
  }
}

// simdjson-java is compiled from its local source checkout: the Maven Central
// artifact (0.3.0) was built against an older incubating Vector API and fails
// on JDK 27 with NoSuchFieldError: VectorOperators.UNSIGNED_LE.
val simdjsonJavaSrc = providers.gradleProperty("simdjsonJavaSrc")
  .getOrElse("/Users/jim/src/symlinks/vector/simdjson-java/src/main/java")

sourceSets {
  create("simdjson") {
    java.srcDir(simdjsonJavaSrc)
  }
}

dependencies {
  jmhImplementation("software.sava:json-iterator")
  jmhImplementation(sourceSets["simdjson"].output)
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

jmh {
  fork = 1
  warmupIterations = 5
  warmup = "1s"
  // Enough measurement iterations that a single noisy iteration (thermal or
  // background activity) cannot dominate the reported confidence interval.
  iterations = 8
  timeOnIteration = "1s"
  // simdjson-java only supports 256/512-bit species; on 128-bit NEON hardware
  // it must be forced to an emulated 256-bit shape.
  jvmArgsAppend.addAll("--add-modules=jdk.incubator.vector", "-Dorg.simdjson.species=256")
}
