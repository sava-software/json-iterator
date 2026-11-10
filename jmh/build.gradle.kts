plugins {
  java
  // Keep in sync with the root build's settings.gradle.kts plugins block.
  id("software.sava.build.feature.jmh") version "21.8.7"
  // Held at jmh-gradle-plugin's version in sava-build's gradle/libs.versions.toml, never
  // ahead of it; its pins in the policy move with it.
  id("me.champeau.jmh") version "0.7.4" apply false
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
