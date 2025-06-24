plugins {
  id("software.sava.build") version "0.1.1"
}

rootProject.name = "json-iterator"

javaModules {
  directory(".") {
    group = "software.sava"
    plugin("software.sava.build.java-module")
  }
}
