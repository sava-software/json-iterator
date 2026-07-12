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

dependencies {
  jmhImplementation("software.sava:json-iterator")
}

// Benchmarks are measurements, not build artifacts: never let Gradle skip
// them as UP-TO-DATE because a results file already exists.
tasks.named("jmh") {
  outputs.upToDateWhen { false }
}

jmh {
  fork = 1
  warmupIterations = 5
  warmup = "1s"
  // Enough measurement iterations that a single noisy iteration (thermal or
  // background activity) cannot dominate the reported confidence interval.
  iterations = 8
  timeOnIteration = "1s"
  // Replicate the long-running-service JVM this library targets: compact
  // object headers (product since JDK 25), generational ZGC (what the sava
  // services run), a pinned pre-touched heap, and no hsperfdata jitter.
  jvmArgsAppend.addAll(
      "-XX:+UseCompactObjectHeaders",
      "-Xms2g", "-Xmx2g",
      "-XX:+AlwaysPreTouch",
      "-XX:+PerfDisableSharedMem",
      "-XX:+UseZGC"
  )
}
