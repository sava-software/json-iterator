pluginManagement {
  // Same local-dev toggle as the root build (see ../settings.gradle.kts): point
  // '-PsavaBuildLocalRepo=<path to sava-build>/build/sava-test-repo' at a published
  // local test repo to bench against an unpublished sava-build change. The publish is
  // NOT automatic — re-run sava-build's publish task after every edit there.
  val savaBuildLocalRepo = providers.gradleProperty("savaBuildLocalRepo")
    .orNull?.takeIf { it.isNotBlank() }
  if (savaBuildLocalRepo != null) {
    logger.warn(
      "sava-build: resolving 'software.sava.build*' plugins from LOCAL repo $savaBuildLocalRepo"
    )
  }
  // 'software.sava.build.feature.jmh' has no published plugin marker (sava-build only
  // publishes markers for ids consumed from a settings 'plugins {}' block), so the
  // published path also resolves by module. Keep the version in sync with the root
  // build's plugins block.
  resolutionStrategy.eachPlugin {
    if (requested.id.id.startsWith("software.sava.build")) {
      if (savaBuildLocalRepo != null) {
        useModule("software.sava:sava-build:0.0.0-test")
      } else {
        useModule("software.sava:sava-build:21.5.16")
      }
    }
  }
  repositories {
    if (savaBuildLocalRepo != null) {
      maven(url = savaBuildLocalRepo)
    }
    gradlePluginPortal()
    mavenCentral()
    // The published path resolves the 'software.sava:sava-build' module from GitHub
    // Packages (it is not on the Plugin Portal or Maven Central), so the same
    // credentials the root build needs are required here.
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
