testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("org.junit.jupiter.params")
  runtimeOnly("org.junit.jupiter.engine")
}

// The Vector API is still incubating in JDK 27 EA (jdk.incubator.vector).
tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

// The dependency-analysis plugin's bundled ASM cannot read Java 27 class files
// (major version 71) yet; disable its tasks until it catches up.
tasks.configureEach {
  if (this::class.java.name.startsWith("com.autonomousapps")) {
    enabled = false
  }
}

tasks.withType<Test>().configureEach {
  jvmArgs("--add-modules=jdk.incubator.vector")
}

tasks.withType<Javadoc>().configureEach {
  (options as CoreJavadocOptions).addStringOption("-add-modules", "jdk.incubator.vector")
}
