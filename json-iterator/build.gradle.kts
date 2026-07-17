plugins {
  id("software.sava.build.feature.hardening")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  requires("org.junit.jupiter.params")
  runtimeOnly("org.junit.jupiter.engine")
}

hardening {
  mutation.register("iterator") {
    // the scan/parse engine and its two source-typed reads — the silent-wrong-answer
    // bugs cluster here (21.0.12 multibyte corruption, chars-path escape stripping)
    targetClasses = listOf(
      "systems.comodal.jsoniter.BaseJsonIterator",
      "systems.comodal.jsoniter.BytesJsonIterator",
      "systems.comodal.jsoniter.CharsJsonIterator",
      "systems.comodal.jsoniter.ValueType",
      "systems.comodal.jsoniter.ValueType\$*"
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
