pluginManagement {
  repositories {
    maven {
      name = "savaGithubPackages"
      url = uri("https://maven.pkg.github.com/sava-software/sava-build")
      credentials(PasswordCredentials::class)
    }
    gradlePluginPortal()
  }
}

plugins {
  id("software.sava.build") version "0.1.21"
}

rootProject.name = "json-iterator"

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
