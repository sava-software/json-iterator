plugins {
  java
  id("software.sava.build.feature.jmh")
}

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(27)
  }
}

// Vector kernels live in their own source set: the JMH bytecode generator
// reflects every class in the jmh source set in a JVM without the incubator
// module, so classes with vector-typed fields must stay out of it. Benches
// in the jmh source set share the kernels' package for package-private access.
sourceSets {
  create("vectorKernels")
}

dependencies {
  "vectorKernelsImplementation"("software.sava:json-iterator")
  jmhImplementation("software.sava:json-iterator")
  jmhImplementation(sourceSets["vectorKernels"].output)
}

// The vector-lab invariant: the library stays byte-identical to main; all
// Vector API experimentation lives in this jmh source set (see the
// systems.comodal.jsoniter.jmh.vector package), so only the benchmark
// build needs the incubator module.
tasks.withType<JavaCompile>().configureEach {
  options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}

jmh {
  jvmArgsAppend.addAll("--add-modules=jdk.incubator.vector")
}
