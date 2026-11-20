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

// sava-build does not ship the dependency-analysis plugin (its README, "Dependency
// analysis"). This build declares it on demand: the plugin joins the settings classpath only
// when the invocation passes '-PdependencyAudit', so an ordinary build, a release build
// among them, neither resolves nor loads it, and has no 'checkModuleDirectivesScope'.
//   ./gradlew -PdependencyAudit checkModuleDirectivesScope
// Any value turns the audit on, '=false' included, so never set the property in a
// gradle.properties file or in a workflow that publishes. The version is
// 'dependency-analysis-gradle-plugin' in sava-build's gradle/libs.versions.toml at the tag
// pinned below, and the verification policy trusts the plugin's chain, which only an audit
// run resolves.
buildscript {
  if (providers.gradleProperty("dependencyAudit").isPresent) {
    dependencies { classpath("com.autonomousapps:dependency-analysis-gradle-plugin:3.20.4") }
  }
}

plugins {
  id("software.sava.build") version "21.8.9"
}

if (providers.gradleProperty("dependencyAudit").isPresent) {
  apply(plugin = "com.autonomousapps.build-health")
}

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
