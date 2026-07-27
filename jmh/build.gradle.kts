plugins {
  java
  // Keep in sync with the root build's settings.gradle.kts plugins block.
  id("software.sava.build.feature.jmh") version "21.5.17"
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
