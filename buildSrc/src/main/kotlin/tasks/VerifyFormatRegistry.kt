package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Verifies that the format registry stays aligned with the rest of the codebase:
 *
 *  - every format in the spec has a matching `*FormatValidator` implementation (and vice versa)
 *  - the generated registry contains an entry for every declared format
 *  - all drafts that map the format behavior option directly agree on the same default
 *  - the public API dump still exposes the format surface the registry relies on
 */
abstract class VerifyFormatRegistry : DefaultTask() {
  // @Internal (not @InputFile): the spec path overlaps an output path declared by
  // generateFormatRegistry. Content is declared via [specContent].
  @get:Internal
  abstract val specFile: RegularFileProperty

  // @Internal: this is the committed generated file, also the generator's output.
  // Verification must read the committed contents instead of triggering regeneration.
  @get:Internal
  abstract val generatedRegistry: RegularFileProperty

  // @Internal on purpose: the directory contains the generated registry (an output of
  // generateFormatRegistry). Declaring it as an input would create an implicit task
  // dependency and make Gradle regenerate before verification. The individual files
  // below (spec, generated registry, API dump) are the declared inputs.
  @get:Internal
  abstract val formatValidatorsDir: DirectoryProperty

  @get:Internal
  abstract val draftConfigsDir: DirectoryProperty

  // @Internal: not generated, but only scanned for text content during verification.
  @get:Internal
  abstract val apiDumpFile: RegularFileProperty

  @get:Input
  val specContent: String
    get() = specFile.get().asFile.readText()

  init {
    group = "verification"
    description = "Verifies the format registry is aligned with validators, draft defaults and the public API"
  }

  @TaskAction
  fun verify() {
    val problems = mutableListOf<String>()
    val formats: List<FormatRegistryGenerator.FormatSpec> =
      FormatRegistryGenerator.parse(specFile.get().asFile)
    require(formats.isNotEmpty()) { "format spec ${specFile.get().asFile} declares no formats" }

    verifyValidators(formats, problems)
    verifyGeneratedEntries(formats, problems)
    verifyDraftDefaults(problems)
    verifyPublicApi(problems)

    if (problems.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("format registry verification failed:")
          problems.forEach { appendLine("  - $it") }
        },
      )
    }
    logger.lifecycle("format registry is aligned: ${formats.size} formats checked")
  }

  private fun verifyValidators(
    formats: List<FormatRegistryGenerator.FormatSpec>,
    problems: MutableList<String>,
  ) {
    val validatorsDir = formatValidatorsDir.get().asFile
    val validatorObjectPattern = Regex("""internal object (\w+FormatValidator)""")
    val implementedValidators = sortedSetOf<String>()
    validatorsDir.listFiles { file -> file.extension == "kt" }?.forEach { file ->
      validatorObjectPattern.findAll(file.readText()).forEach { implementedValidators += it.groupValues[1] }
    }
    if (implementedValidators.isEmpty()) {
      problems += "no format validator objects found in ${project.relativePath(validatorsDir)}"
      return
    }
    val declaredValidators = formats.map { it.validator }.toSet()
    for (format in formats) {
      if (format.validator !in implementedValidators) {
        problems +=
          "format '${format.name}': validator ${format.validator} has no implementation " +
          "in ${project.relativePath(validatorsDir)}"
      }
    }
    for (orphan in implementedValidators - declaredValidators) {
      problems += "validator $orphan is implemented but not registered in ${project.relativePath(specFile.get().asFile)}"
    }
  }

  private fun verifyGeneratedEntries(
    formats: List<FormatRegistryGenerator.FormatSpec>,
    problems: MutableList<String>,
  ) {
    val generatedFile = generatedRegistry.get().asFile
    if (!generatedFile.isFile) {
      problems +=
        "generated registry ${project.relativePath(generatedFile)} is missing " +
          "(run './gradlew :json-schema-validator:generateFormatRegistry')"
      return
    }
    val content = generatedFile.readText()
    for (format in formats) {
      if ("\"${format.name}\" to ${format.validator}" !in content) {
        problems +=
          "generated registry ${project.relativePath(generatedFile)} has no entry " +
          "'\"${format.name}\" to ${format.validator}'"
      }
    }
  }

  private fun verifyDraftDefaults(problems: MutableList<String>) {
    val configsDir = draftConfigsDir.get().asFile
    val configFiles =
      configsDir.listFiles { file ->
        file.name.startsWith("Draft") && file.name.endsWith("SchemaLoaderConfig.kt")
      }?.sortedBy { it.name }.orEmpty()
    if (configFiles.isEmpty()) {
      problems += "no draft loader configs found in ${project.relativePath(configsDir)}"
      return
    }
    val nullDefaultPattern =
      Regex("""null,\s*(?:FormatBehavior\.)?(ANNOTATION_AND_ASSERTION|ANNOTATION_ONLY)\s*->\s*FormatAssertionFactory\.(\w+)""")
    val directDefaults = sortedMapOf<String, String>()
    for (config in configFiles) {
      val content = config.readText()
      if ("FORMAT_BEHAVIOR_OPTION" !in content) {
        problems += "draft config ${config.name} does not wire SchemaOption.FORMAT_BEHAVIOR_OPTION"
        continue
      }
      val defaults = nullDefaultPattern.findAll(content).map { it.groupValues[2] }.toList()
      if (defaults.isNotEmpty()) {
        directDefaults[config.name] = defaults.distinct().joinToString("+")
      }
    }
    val distinctDefaults = directDefaults.values.toSet()
    if (distinctDefaults.size > 1) {
      problems +=
        "draft configs disagree on the default format behavior: " +
          directDefaults.entries.joinToString(", ") { (file, default) -> "$file=$default" }
    }
    logger.lifecycle("default format behavior per draft: $directDefaults")
  }

  private fun verifyPublicApi(problems: MutableList<String>) {
    val apiFile = apiDumpFile.get().asFile
    if (!apiFile.isFile) {
      problems += "public API dump ${project.relativePath(apiFile)} is missing"
      return
    }
    val content = apiFile.readText()
    val requiredEntries =
      listOf(
        "io/github/optimumcode/json/schema/FormatValidator" to "public FormatValidator interface",
        "io/github/optimumcode/json/schema/FormatBehavior" to "public FormatBehavior enum",
        "ANNOTATION_AND_ASSERTION" to "FormatBehavior.ANNOTATION_AND_ASSERTION entry",
        "ANNOTATION_ONLY" to "FormatBehavior.ANNOTATION_ONLY entry",
        "withCustomFormats" to "JsonSchemaLoader.withCustomFormats",
      )
    for ((needle, description) in requiredEntries) {
      if (needle !in content) {
        problems += "public API dump ${project.relativePath(apiFile)} does not contain $description ('$needle')"
      }
    }
  }
}