package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Verifies that the format registry in FormatAssertionFactory,
 * the format validator implementations and the exported public API
 * stay aligned, and that every draft loader config uses the same
 * default format behavior toggle.
 */
abstract class VerifyFormatRegistry : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val registryFile: RegularFileProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val formatsDir: DirectoryProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val configsDir: DirectoryProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val apiDumpFile: RegularFileProperty

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks format registry, format validators and exported API are aligned"
  }

  @TaskAction
  fun verify() {
    val problems = mutableListOf<String>()

    val registrySource = registryFile.get().asFile.readText()
    val registryBlock =
      KNOWN_FORMATS_BLOCK.find(registrySource)?.groupValues?.get(1)
        ?: throw GradleException(
          "cannot locate the KNOWN_FORMATS map in ${registryFile.get().asFile.rel()}: " +
            "the format registry gate cannot verify anything and must not pass silently",
        )
    val registry: Map<String, String> =
      REGISTRY_ENTRY
        .findAll(registryBlock)
        .associate { it.groupValues[1] to it.groupValues[2] }
    if (registry.isEmpty()) {
      problems += "KNOWN_FORMATS in ${registryFile.get().asFile.rel()} contains no entries"
    }

    val validators: List<ValidatorDecl> =
      formatsDir
        .get()
        .asFile
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
          VALIDATOR_DECLARATION.findAll(file.readText()).map { match ->
            ValidatorDecl(
              name = match.groupValues[2],
              isAbstract = match.groupValues[1].isNotEmpty(),
              file = file,
            )
          }
        }.toList()
    val concreteValidators = validators.filter { !it.isAbstract }

    for ((format, validatorName) in registry) {
      if (concreteValidators.none { it.name == validatorName }) {
        problems +=
          "format '$format' is registered to $validatorName in ${registryFile.get().asFile.rel()} " +
            "but no validator class with that name exists under ${formatsDir.get().asFile.rel()}"
      }
    }
    for (decl in concreteValidators) {
      if (decl.name !in registry.values) {
        problems +=
          "validator ${decl.name} declared in ${decl.file.rel()} is not registered " +
            "in KNOWN_FORMATS of ${registryFile.get().asFile.rel()}"
      }
    }

    var parsedConfigs = 0
    configsDir
      .get()
      .asFile
      .walkTopDown()
      .filter { it.isFile && CONFIG_FILE_NAME.matches(it.name) }
      .forEach { configFile ->
        val source = configFile.readText()
        val whenBlock = FORMAT_BEHAVIOR_WHEN.find(source)?.groupValues?.get(1)
        when {
          // legacy drafts (4, 6, 7): an explicit when maps the option to a factory
          whenBlock != null -> {
            parsedConfigs += 1
            val normalized = whenBlock.replace("FormatBehavior.", "").replace(WHITESPACE, "")
            if (ASSERTION_DEFAULT_BRANCH !in normalized || ANNOTATION_ONLY_BRANCH !in normalized) {
              problems +=
                "format default toggle in ${configFile.rel()} differs from the shared default " +
                  "(expected: null and ANNOTATION_AND_ASSERTION -> AnnotationAndAssertion, " +
                  "ANNOTATION_ONLY -> AnnotationOnly)"
            }
          }

          // vocabulary drafts (2019-09, 2020-12): the explicit option must override
          // the vocabulary-driven default instead of replacing it
          "FORMAT_BEHAVIOR_OPTION" in source -> {
            parsedConfigs += 1
            val normalized = source.replace("FormatBehavior.", "").replace(WHITESPACE, "")
            if (VOCABULARY_FALLBACK.none { it in normalized }) {
              problems +=
                "format default toggle in ${configFile.rel()} no longer falls back to the " +
                  "format-assertion vocabulary when FORMAT_BEHAVIOR_OPTION is not set " +
                  "(expected: options[SchemaOption.FORMAT_BEHAVIOR_OPTION]?.let { it == " +
                  "ANNOTATION_AND_ASSERTION } ?: vocabulary.enabled(...))"
            }
          }

          else ->
            problems +=
              "draft loader config ${configFile.rel()} has no FORMAT_BEHAVIOR_OPTION handling: " +
                "every draft must define its format default explicitly"
        }
      }
    if (parsedConfigs == 0) {
      problems +=
        "no draft loader configs with a FORMAT_BEHAVIOR_OPTION branch were parsed under " +
          "${configsDir.get().asFile.rel()}: the gate cannot verify format defaults and must not pass silently"
    }

    val apiDump = apiDumpFile.get().asFile.readText()
    for ((snippet, description) in REQUIRED_API_SNIPPETS) {
      if (snippet !in apiDump) {
        problems +=
          "exported API dump ${apiDumpFile.get().asFile.rel()} is missing '$snippet' ($description): " +
            "the format registry is no longer aligned with the public API"
      }
    }

    writeReport(registry, concreteValidators, parsedConfigs, problems)
    if (problems.isNotEmpty()) {
      throw GradleException(
        problems.joinToString(
          prefix = "format registry verification failed with ${problems.size} problem(s):\n - ",
          separator = "\n - ",
        ),
      )
    }
    logger.lifecycle(
      "format registry OK: {} formats, {} validators, {} draft configs checked",
      registry.size,
      concreteValidators.size,
      parsedConfigs,
    )
  }

  private fun writeReport(
    registry: Map<String, String>,
    validators: List<ValidatorDecl>,
    parsedConfigs: Int,
    problems: List<String>,
  ) {
    val out = reportFile.get().asFile
    out.parentFile.mkdirs()
    out.printWriter().use { writer ->
      writer.println("format registry verification report")
      writer.println("registered formats: ${registry.size}")
      registry.toSortedMap().forEach { (format, validator) ->
        writer.println("  $format -> $validator")
      }
      writer.println("concrete validators: ${validators.size}")
      writer.println("draft configs checked: $parsedConfigs")
      if (problems.isEmpty()) {
        writer.println("result: OK")
      } else {
        writer.println("result: FAILED")
        problems.forEach { writer.println("  - $it") }
      }
    }
  }

  private fun File.rel(): String = relativeTo(project.rootDir).invariantSeparatorsPath

  private data class ValidatorDecl(
    val name: String,
    val isAbstract: Boolean,
    val file: File,
  )

  private companion object {
    val KNOWN_FORMATS_BLOCK =
      Regex("KNOWN_FORMATS[^=]*=\\s*mapOf\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
    val REGISTRY_ENTRY = Regex("\"([^\"]+)\"\\s+to\\s+(\\w+)")
    val VALIDATOR_DECLARATION =
      Regex("internal\\s+(abstract\\s+)?(?:object|class)\\s+(\\w+FormatValidator)\\b")
    val CONFIG_FILE_NAME = Regex("Draft\\w*SchemaLoaderConfig\\.kt")
    val FORMAT_BEHAVIOR_WHEN =
      Regex(
        "when\\s*\\(options\\[SchemaOption\\.FORMAT_BEHAVIOR_OPTION\\]\\)\\s*\\{(.*?)\\}",
        RegexOption.DOT_MATCHES_ALL,
      )
    val WHITESPACE = Regex("\\s+")
    const val ASSERTION_DEFAULT_BRANCH =
      "null,ANNOTATION_AND_ASSERTION->FormatAssertionFactory.AnnotationAndAssertion"
    const val ANNOTATION_ONLY_BRANCH = "ANNOTATION_ONLY->FormatAssertionFactory.AnnotationOnly"
    val VOCABULARY_FALLBACK =
      listOf(
        "options[SchemaOption.FORMAT_BEHAVIOR_OPTION]",
        "?.let{it==ANNOTATION_AND_ASSERTION}?:vocabulary.enabled(",
      )
    val REQUIRED_API_SNIPPETS =
      listOf(
        "class io/github/optimumcode/json/schema/FormatValidator {" to "FormatValidator interface",
        "field ANNOTATION_AND_ASSERTION Lio/github/optimumcode/json/schema/FormatBehavior;" to
          "FormatBehavior.ANNOTATION_AND_ASSERTION enum entry",
        "field ANNOTATION_ONLY Lio/github/optimumcode/json/schema/FormatBehavior;" to
          "FormatBehavior.ANNOTATION_ONLY enum entry",
        "field FORMAT_BEHAVIOR_OPTION Lio/github/optimumcode/json/schema/SchemaOption;" to
          "SchemaOption.FORMAT_BEHAVIOR_OPTION option",
        "class io/github/optimumcode/json/schema/FormatValidationResult {" to "FormatValidationResult type",
        "withCustomFormat (Ljava/lang/String;Lio/github/optimumcode/json/schema/FormatValidator;)" to
          "JsonSchemaLoader.withCustomFormat extension point",
      )
  }
}
