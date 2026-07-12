import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  java
  id("software.sava.build.feature.jmh")
  id("com.gradleup.shadow") version "9.5.1"
}

repositories {
  mavenCentral()
  val gprUser = providers.gradleProperty("savaGithubPackagesUsername").orNull?.takeIf { it.isNotBlank() }
  val gprToken = providers.gradleProperty("savaGithubPackagesPassword").orNull?.takeIf { it.isNotBlank() }
  if (gprUser != null && gprToken != null) {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/json-iterator")
      credentials {
        username = gprUser
        password = gprToken
      }
    }
  }
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(27)
  }
}

// The published release (built from main) is the pre-vectorization
// baseline. It shares this project's package names, so it is relocated to
// jsoniter.published before joining the benchmark classpath; its
// configuration also opts out of the includeBuild substitution that maps
// software.sava:json-iterator to the local project.
val publishedJsonIterator: Configuration by configurations.creating {
  resolutionStrategy.useGlobalDependencySubstitutionRules.set(false)
}

val relocatePublishedJsonIterator = tasks.register<ShadowJar>("relocatePublishedJsonIterator") {
  configurations = listOf(publishedJsonIterator)
  relocate("systems.comodal.jsoniter", "jsoniter.published")
  exclude("module-info.class")
  archiveBaseName = "json-iterator-relocated"
  archiveClassifier = ""
  archiveVersion = "25.1.0"
}

dependencies {
  jmhImplementation("software.sava:json-iterator")
  jmhImplementation("org.simdjson:simdjson-java:0.4.0")
  publishedJsonIterator("software.sava:json-iterator:25.1.0")
  jmhImplementation(files(relocatePublishedJsonIterator.flatMap { it.archiveFile }))
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
