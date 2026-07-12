import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  java
  id("software.sava.build.feature.jmh")
  id("com.gradleup.shadow") version "9.5.1"
}

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(27)
  }
}

// The published 21.1.0 release is the pre-vectorization (Java 21) baseline. It shares
// this project's package names, so it is relocated to jsoniter.v21 before
// joining the benchmark classpath; its configuration also opts out of the
// includeBuild substitution that maps software.sava:json-iterator to the
// local project.
val oldJsonIterator: Configuration by configurations.creating {
  resolutionStrategy.useGlobalDependencySubstitutionRules.set(false)
}

val relocateOldJsonIterator = tasks.register<ShadowJar>("relocateOldJsonIterator") {
  configurations = listOf(oldJsonIterator)
  relocate("systems.comodal.jsoniter", "jsoniter.v21")
  exclude("module-info.class")
  archiveBaseName = "json-iterator-relocated"
  archiveClassifier = ""
  archiveVersion = "21.1.0"
}

dependencies {
  jmhImplementation("software.sava:json-iterator")
  jmhImplementation("org.simdjson:simdjson-java:0.4.0")
  oldJsonIterator("software.sava:json-iterator:21.1.0")
  jmhImplementation(files(relocateOldJsonIterator.flatMap { it.archiveFile }))
}

tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

// Run defaults, '-Pjmh*' overrides, service JVM flags, and results archiving
// come from software.sava.build.feature.jmh; this block only appends the
// vector-specific arguments on top.
jmh {
  // simdjson-java only supports 256/512-bit species; on 128-bit NEON hardware
  // it must be forced to an emulated 256-bit shape.
  jvmArgsAppend.addAll("--add-modules=jdk.incubator.vector", "-Dorg.simdjson.species=256")
}
