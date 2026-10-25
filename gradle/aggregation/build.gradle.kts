plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  centralPortalAggregation(project(":json-iterator"))
}
