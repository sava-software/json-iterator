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
      // test, fuzz, and factory sources share the recompiled root
      "systems.comodal.jsoniter.Test*",
      "systems.comodal.jsoniter.*Fuzz",
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
    targetTests = "systems.comodal.jsoniter.Test*"
  }
  mutation.register("numbers") {
    // PowersOfFive is deliberately not a target: constant tables produce slow,
    // low-value mutants; table errors surface as killed DoubleParser mutants anyway
    targetClasses = listOf("systems.comodal.jsoniter.DoubleParser")
    targetTests = "systems.comodal.jsoniter.Test*"
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
    targetTests = "systems.comodal.jsoniter.Test*"
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

// --- mutation ratchet -------------------------------------------------------
// Every pitest run diffs its unkilled mutants (SURVIVED and NO_COVERAGE)
// against the checked-in baseline under config/pitest/ and fails on anything
// new: a fresh mutant must be killed with a test or knowingly accepted via
// -PupdateMutationBaseline (policy in config/pitest/README.md).
listOf("iterator", "numbers", "util").forEach { suiteName ->
  val taskSuffix = suiteName.replaceFirstChar(Char::uppercase)
  val verify = tasks.register("pitest${taskSuffix}Verify") {
    description = "Fails when the '$suiteName' PIT run left unkilled mutants missing from the accepted baseline."
    val csvProvider = layout.buildDirectory.file("reports/pitest/$suiteName/mutations.csv")
    val baselineFile = layout.projectDirectory.file("config/pitest/$suiteName-accepted.csv").asFile
    val update = providers.gradleProperty("updateMutationBaseline").isPresent
    doLast {
      val csv = csvProvider.get().asFile
      if (!csv.exists()) {
        throw GradleException("no PIT report at $csv — run pitest$taskSuffix first")
      }
      val gated = setOf("SURVIVED", "NO_COVERAGE")
      val current = csv.readLines().mapNotNull { line ->
        val parts = line.split(',')
        if (parts.size < 6 || parts[5] !in gated) {
          null
        } else {
          // class,method,line,mutator,status — line numbers churn on refactors;
          // refresh the baseline when they do
          "${parts[1]},${parts[3]},${parts[4]},${parts[2].substringAfterLast('.')},${parts[5]}"
        }
      }.toSortedSet()
      if (update) {
        baselineFile.parentFile.mkdirs()
        baselineFile.writeText(current.joinToString("\n", postfix = "\n"))
        logger.lifecycle("pitest baseline '$suiteName': wrote ${current.size} accepted entries")
        return@doLast
      }
      val accepted = if (baselineFile.exists()) {
        baselineFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
      } else {
        emptySet()
      }
      val fresh = current - accepted
      val stale = accepted - current
      if (stale.isNotEmpty()) {
        logger.lifecycle("pitest baseline '$suiteName': ${stale.size} stale entries (since killed or moved) — refresh with -PupdateMutationBaseline")
      }
      if (fresh.isNotEmpty()) {
        throw GradleException(
            "pitest '$suiteName': ${fresh.size} unkilled mutant(s) not in the accepted baseline:\n" +
                fresh.joinToString("\n") +
                "\nKill them with tests, or accept knowingly by re-running with -PupdateMutationBaseline " +
                "and documenting the reason in config/pitest/README.md."
        )
      }
    }
  }
  tasks.named("pitest$taskSuffix") {
    finalizedBy(verify)
  }
}
