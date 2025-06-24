plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  nmcpAggregation(project(":json-iterator"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(":json-iterator:publish")
}
