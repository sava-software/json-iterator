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

jmh {
  fork = 1
  warmupIterations = 5
  warmup = "1s"
  // Enough measurement iterations that a single noisy iteration (thermal or
  // background activity) cannot dominate the reported confidence interval.
  iterations = 8
  timeOnIteration = "1s"
}
