package io.github.optimumcode.json.schema.gates

private const val CHECK_NAME: String = "format-registry"

private val CONCRETE_VALIDATOR_DECLARATION = Regex("""internal\s+object\s+(\w+FormatValidator)\b""")
private val ABSTRACT_VALIDATOR_DECLARATION = Regex("""internal\s+abstract\s+class\s+(\w+FormatValidator)\b""")
private val REGISTRY_ENTRY = Regex(""""([a-z0-9-]+)"\s+to\s+(\w+FormatValidator)\b""")
private val ASSERTION_DEFAULT =
  Regex("""null,\s*(?:FormatBehavior\.)?ANNOTATION_AND_ASSERTION\s*->""")
private val VOCABULARY_DEFAULT = Regex(""":\s*vocabulary\.enabled\(""")

private const val DEFAULT_ASSERTION: String = "ASSERTION"
private const val DEFAULT_VOCABULARY: String = "VOCABULARY"

/**
 * Checks that the internal format registry stays aligned with the code and the
 * exported API:
 *
 * 1. every concrete `internal object *FormatValidator` in `internal/formats` is
 *    registered in `FormatAssertionFactory.KNOWN_FORMATS`;
 * 2. every validator class referenced by `KNOWN_FORMATS` exists as a concrete
 *    validator class (a missing class fails the gate);
 * 3. the default format behavior of every draft config matches the declared
 *    expectation (a different default flag fails the gate);
 * 4. the exported API dump exposes the format API surface
 *    (`FormatValidator`, `FormatBehavior`, custom-format registration).
 */
public object FormatRegistryChecker {
  /**
   * @param formatSources file name to content for every file in the formats package
   * @param registrySource content of `FormatAssertionFactory.kt`
   * @param configSources config file name to content for the draft loader configs
   * @param apiDump content of the binary-compatibility API dump
   * @param expectations declared expectations loaded from the fixtures directory
   */
  public fun check(
    formatSources: Map<String, String>,
    registrySource: String,
    configSources: Map<String, String>,
    apiDump: String,
    expectations: FormatRegistryExpectations,
  ): List<GateViolation> {
    val violations = mutableListOf<GateViolation>()
    violations += checkRegistryAlignment(formatSources, registrySource)
    violations += checkDraftDefaults(configSources, expectations.draftFormatDefaults)
    violations += checkExportedApi(apiDump, expectations.exportedApiFragments)
    return violations
  }

  private fun checkRegistryAlignment(
    formatSources: Map<String, String>,
    registrySource: String,
  ): List<GateViolation> {
    val violations = mutableListOf<GateViolation>()
    val concreteValidators = sortedSetOf<String>()
    for ((fileName, content) in formatSources) {
      val abstract = ABSTRACT_VALIDATOR_DECLARATION.find(content)
      val concrete = CONCRETE_VALIDATOR_DECLARATION.find(content)
      if (abstract != null && concrete == null) {
        continue
      }
      if (concrete == null) {
        continue
      }
      val className = concrete.groupValues[1]
      val expectedFile = "$className.kt"
      if (fileName != expectedFile) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "format validator class $className is declared in $fileName " +
              "but the convention requires it to live in $expectedFile",
          )
      }
      concreteValidators += className
    }

    val registered = REGISTRY_ENTRY.findAll(registrySource).toList()
    if (registered.isEmpty()) {
      violations +=
        GateViolation(
          CHECK_NAME,
          "no format registrations were found in the registry source; " +
            "the KNOWN_FORMATS map could not be located",
        )
      return violations
    }
    val registeredClasses = sortedSetOf<String>()
    for (entry in registered) {
      val formatKey = entry.groupValues[1]
      val className = entry.groupValues[2]
      registeredClasses += className
      if (className !in concreteValidators) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "format '$formatKey' is registered to $className but no concrete validator " +
              "class with that name exists in the formats package; " +
              "the registered class is missing",
          )
      }
    }
    for (className in concreteValidators) {
      if (className !in registeredClasses) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "format validator $className exists in the formats package " +
              "but is not registered in KNOWN_FORMATS",
          )
      }
    }
    return violations
  }

  private fun checkDraftDefaults(
    configSources: Map<String, String>,
    draftDefaults: List<DraftFormatDefault>,
  ): List<GateViolation> {
    val violations = mutableListOf<GateViolation>()
    for (draftDefault in draftDefaults) {
      val source = configSources[draftDefault.configFile]
      if (source == null) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "draft '${draftDefault.draft}' expects config file ${draftDefault.configFile} " +
              "but the file was not found",
          )
        continue
      }
      val hasAssertionDefault = ASSERTION_DEFAULT.containsMatchIn(source)
      val hasVocabularyDefault = VOCABULARY_DEFAULT.containsMatchIn(source)
      val actualDefault =
        when {
          hasAssertionDefault && !hasVocabularyDefault -> DEFAULT_ASSERTION
          hasVocabularyDefault && !hasAssertionDefault -> DEFAULT_VOCABULARY
          else -> null
        }
      if (actualDefault == null) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "draft '${draftDefault.draft}' (${draftDefault.configFile}) has an unrecognized " +
              "default format behavior structure (assertion-default pattern present: " +
              "$hasAssertionDefault, vocabulary-default pattern present: $hasVocabularyDefault); " +
              "update the gate expectations if the structure changed intentionally",
          )
        continue
      }
      if (actualDefault != draftDefault.expectedDefault) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "draft '${draftDefault.draft}' (${draftDefault.configFile}) defaults format " +
              "behavior to $actualDefault but ${draftDefault.expectedDefault} is declared in " +
              "the expectations; the format default flag differs from the declared value",
          )
      }
    }
    return violations
  }

  private fun checkExportedApi(
    apiDump: String,
    exportedApiFragments: List<String>,
  ): List<GateViolation> =
    exportedApiFragments
      .filterNot(apiDump::contains)
      .map { fragment ->
        GateViolation(
          CHECK_NAME,
          "exported API dump does not contain '$fragment'; " +
            "the format API surface is not aligned with the format registry",
        )
      }
}
