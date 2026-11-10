plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("org.junit.jupiter.params")
  // ManagementFactory + com.sun.management.ThreadMXBean, for the
  // allocation-bound assertions
  requires("java.management")
  requires("jdk.management")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("iterator") {
    // catch-all by exclusion, so a new class is mutated by default instead of
    // silently skipped (the JsonIterator default methods went unmutated for a
    // day under the old allowlist). The scan/parse engine lives here — the
    // silent-wrong-answer bugs cluster in it (21.0.12 multibyte corruption,
    // chars-path escape stripping)
    targetClasses = listOf("systems.comodal.jsoniter.*")
    excludedClasses = listOf(
      // test, fuzz, and factory sources share the recompiled root; trailing
      // wildcard so nested harness helpers (JsonFuzz$Stub) stay excluded too
      "systems.comodal.jsoniter.Test*",
      "systems.comodal.jsoniter.*Fuzz*",
      "systems.comodal.jsoniter.factories.*",
      // covered by the 'numbers' suite; PowersOfFive deliberately untargeted —
      // constant tables produce slow, low-value mutants and table errors
      // surface as killed DoubleParser mutants anyway
      "systems.comodal.jsoniter.DoubleParser",
      "systems.comodal.jsoniter.PowersOfFive",
      // covered by the 'util' suite
      "systems.comodal.jsoniter.JHex",
      "systems.comodal.jsoniter.JHex\$*",
      "systems.comodal.jsoniter.JIUtil",
      "systems.comodal.jsoniter.InstantParser",
      "systems.comodal.jsoniter.FieldMatcher",
      "systems.comodal.jsoniter.FieldMatcher\$*"
    )
    targetTests = "systems.comodal.jsoniter.Test*,systems.comodal.jsoniter.*SeedReplayTest"
    // no suite targets PowersOfFive, so the exclusion audit rightly calls it an
    // unowned hole rather than a partition handoff — argued, not forgotten
    declineExclusionAudit(
      "systems.comodal.jsoniter.PowersOfFive",
      "constant power-of-five tables: mutants are slow and low-value, and a wrong "
          + "table entry surfaces as a killed DoubleParser mutant in the 'numbers' suite",
    )
    // STRONGER misses dropped fluent calls (receiver-returning expressions);
    // NAKED_RECEIVER fired 16 mutants here in trial — numbers in config/pitest/README.md
    mutators = "STRONGER,EXPERIMENTAL_NAKED_RECEIVER"
  }
  mutation.register("numbers") {
    // PowersOfFive is deliberately not a target: constant tables produce slow,
    // low-value mutants; table errors surface as killed DoubleParser mutants anyway
    targetClasses = listOf("systems.comodal.jsoniter.DoubleParser")
    targetTests = "systems.comodal.jsoniter.Test*,systems.comodal.jsoniter.*SeedReplayTest"
  }
  mutation.register("util") {
    targetClasses = listOf(
      "systems.comodal.jsoniter.JHex",
      "systems.comodal.jsoniter.JHex\$*",
      "systems.comodal.jsoniter.JIUtil",
      "systems.comodal.jsoniter.InstantParser",
      "systems.comodal.jsoniter.FieldMatcher",
      "systems.comodal.jsoniter.FieldMatcher\$*"
    )
    targetTests = "systems.comodal.jsoniter.Test*,systems.comodal.jsoniter.*SeedReplayTest"
  }
  fuzz.register("json") {
    targetClass = "systems.comodal.jsoniter.JsonFuzz"
    // every interesting boundary is word- or token-local; larger documents only
    // slow executions down. Depth is bounded by the harness, not the length
    maxLen = 8192
    // escapes, multibyte runs, surrogate pairs, and tricky numbers: content a
    // from-scratch mutator takes a long time to assemble into one valid document
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/json")
  }
  fuzz.register("double") {
    targetClass = "systems.comodal.jsoniter.DoubleFuzz"
    // the longest interesting tokens (subnormal boundaries, overflow exponents)
    // fit well under 64 characters
    maxLen = 64
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/double")
  }
  fuzz.register("number") {
    // the integer readers accumulate digit-at-a-time, a separate path from the
    // double token scan
    targetClass = "systems.comodal.jsoniter.NumberFuzz"
    maxLen = 64
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/number")
  }
  fuzz.register("instant") {
    targetClass = "systems.comodal.jsoniter.InstantFuzz"
    // ISO and RFC-1123 timestamps top out around 40 chars; headroom probes
    // over-long fraction and zone tails
    maxLen = 128
    seedCorpus = layout.projectDirectory.dir("src/test/resources/fuzz/instant")
  }
}

// PIT 1.30.2's JSpecify filter drops every NULL_RETURNS mutant of a method under a
// @NullMarked default before it runs, and the ratchet compares only unkilled rows, so a
// nullness annotation arriving in any main or test source directory (the PIT recompile
// merges them into one root; generated test sources included) would silently remove
// those kills. Nothing here is annotated. This fails 'check' and every PIT suite the day
// something is, so the filter's effect is decided on purpose rather than discovered from
// a population line — HARDENING_NOTES.md, "The mutator set". module-info.java is exempt:
// the recompile strips it, so an annotation there never reaches PIT. Lines that are
// '//' comments are ignored; a block comment naming the annotation still trips it.
val jspecifyGuard = tasks.register("jspecifyGuard") {
  description = "Fails when a JSpecify nullness annotation appears in the main or test sources"
  // local vals only: a script-level reference captured by doLast is not configuration-cacheable
  val sources = files(sourceSets.main.get().allJava, sourceSets.test.get().allJava)
    .asFileTree.matching { exclude("**/module-info.java") }
  val projectDirectory = layout.projectDirectory.asFile
  val marker = layout.buildDirectory.file("hardening/jspecifyGuard.ok")
  inputs.files(sources)
  outputs.file(marker)
  doLast {
    // the lookahead sits before the indent so backtracking cannot skip it
    val pattern = Regex("""^(?!\s*//).*?(@NullMarked\b|\borg\.jspecify\b)""")
    val hits = sources.files
      .filter { f -> f.readLines().any { line -> pattern.containsMatchIn(line) } }
      .map { it.relativeTo(projectDirectory).path }
      .sorted()
    if (hits.isNotEmpty()) {
      throw GradleException(
        "JSpecify annotations in the mutated sources: $hits. PIT 1.30.2's JSpecify filter would " +
            "drop the NULL_RETURNS mutants under a @NullMarked default without a status; decide " +
            "the filter on purpose first (HARDENING_NOTES.md, \"The mutator set\")."
      )
    }
    marker.get().asFile.apply { parentFile.mkdirs(); writeText("no JSpecify annotations\n") }
  }
}
tasks.named("check") { dependsOn(jspecifyGuard) }
// the hazard lands when a suite runs, and the local gate runs the suites outside 'check'
tasks.named { it.startsWith("pitest") }.configureEach { dependsOn(jspecifyGuard) }

// The mutation ratchet ('pitest<Suite>Verify' against config/pitest/, plus the
// 'qualityGate' aggregate) comes from the hardening convention plugin.
