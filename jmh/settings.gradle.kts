pluginManagement {
  // Same local-dev toggle as the root build (see ../settings.gradle.kts): point
  // '-PsavaBuildLocalRepo=<path to sava-build>/build/sava-test-repo' at a published
  // local test repo to bench against an unpublished sava-build change. The publish is
  // NOT automatic — re-run sava-build's publish task after every edit there.
  val savaBuildLocalRepo = providers.gradleProperty("savaBuildLocalRepo")
    .orNull?.takeIf { it.isNotBlank() }
  if (savaBuildLocalRepo != null) {
    val metadata = settingsDir.resolve(savaBuildLocalRepo)
      .resolve("software/sava/sava-build/maven-metadata.xml")
    val age = if (metadata.isFile) {
      val minutes = (System.currentTimeMillis() - metadata.lastModified()) / 60_000
      "0.0.0-test published ${if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"} ago"
    } else {
      "NO 0.0.0-test PUBLISH FOUND — run sava-build's publish task"
    }
    logger.warn(
      "sava-build: resolving 'software.sava.build*' plugins from LOCAL repo $savaBuildLocalRepo ($age)"
    )
    // Only the local path needs this: the test repo carries no plugin markers, so the
    // id has to be rewritten to the module. The published path resolves through the
    // marker sava-build publishes for every id (21.5.17+), from the version in
    // build.gradle.kts's plugins block.
    resolutionStrategy.eachPlugin {
      if (requested.id.id.startsWith("software.sava.build")) {
        useModule("software.sava:sava-build:0.0.0-test")
      }
    }
  }
  repositories {
    if (savaBuildLocalRepo != null) {
      maven(url = savaBuildLocalRepo)
    }
    gradlePluginPortal()
    mavenCentral()
    // sava-build publishes its markers and module to GitHub Packages only (neither is
    // on the Plugin Portal or Maven Central), so the same credentials the root build
    // needs are required here.
    val gprUser = providers.gradleProperty("savaGithubPackagesUsername")
      .orNull?.takeIf { it.isNotBlank() }
    val gprToken = providers.gradleProperty("savaGithubPackagesPassword")
      .orNull?.takeIf { it.isNotBlank() }
    if (gprUser != null && gprToken != null) {
      maven {
        name = "savaGithubPackages"
        url = uri("https://maven.pkg.github.com/sava-software/sava-build")
        credentials {
          username = gprUser
          password = gprToken
        }
      }
    }
  }
}

rootProject.name = "json-iterator-jmh"

includeBuild("..")
