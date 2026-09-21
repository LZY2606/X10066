package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Generates `GeneratedFormatRegistry.kt` from the declarative format spec (`formats/formats.json`).
 * The generated file is committed to the repository; [VerifyGeneratedSources] fails the build
 * if the committed file drifts from the spec.
 */
abstract class GenerateFormatRegistry : DefaultTask() {
  @get:InputFile
  abstract val specFile: RegularFileProperty

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  init {
    group = "generation"
    description = "Generates the format registry source from formats/formats.json"
  }

  @TaskAction
  fun generate() {
    val formats: List<FormatRegistryGenerator.FormatSpec> =
      FormatRegistryGenerator.parse(specFile.get().asFile)
    outputFile.get().asFile.writeText(FormatRegistryGenerator.generate(formats))
    logger.lifecycle("generated ${formats.size} format entries into ${outputFile.get().asFile}")
  }
}