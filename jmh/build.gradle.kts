plugins {
  java
  id("software.sava.build.feature.jmh")
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
