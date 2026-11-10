rootProject.name = "json-iterator"

pluginManagement {
  // Point '-PsavaBuildLocalRepo=<path to sava-build>/build/sava-test-repo' (or set it in
  // ~/.gradle/gradle.properties) at a local sava-build checkout to build against an
  // unpublished plugin change; sava-build publishes that repo with
  //   ./gradlew publishSavaBuildTestPublicationToSavaTestRepoRepository
  // and every id below then resolves to the 0.0.0-test module regardless of the
  // version the plugins block requests — which the plugin announces at the end of
  // each build, so this block stays silent. The useModule call also bypasses plugin
  // markers, which the test repo does not contain. That module is unsigned, so the
  // strict dependency verification policy refuses it: every command that sets the
  // property (all of them, while it sits in ~/.gradle) also passes
  // '--dependency-verification lenient', which never goes in a gradle.properties.
  val savaBuildLocalRepo = providers.gradleProperty("savaBuildLocalRepo")
    .orNull?.takeIf { it.isNotBlank() }
  if (savaBuildLocalRepo != null) {
    resolutionStrategy.eachPlugin {
      if (requested.id.id.startsWith("software.sava.build")) {
        useModule("software.sava:sava-build:0.0.0-test")
      }
    }
  }
  repositories {
    if (savaBuildLocalRepo != null) {
      maven(url = savaBuildLocalRepo) {
        content {
          includeModule("software.sava", "sava-build")
          includeGroupByRegex("software[.]sava[.]build([.].*)?")
        }
      }
    }
    gradlePluginPortal()
    mavenCentral()
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername")
      .orNull?.takeIf { it.isNotBlank() }
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword")
      .orNull?.takeIf { it.isNotBlank() }
    if (gprUser != null && gprToken != null) {
      maven {
        name = "savaGithubPackages"
        url = uri("https://maven.pkg.github.com/sava-software/sava-build")
        content {
          includeModule("software.sava", "sava-build")
          includeGroupByRegex("software[.]sava[.]build([.].*)?")
        }
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    }
  }
}

plugins {
  id("software.sava.build") version "21.8.7"
}

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
