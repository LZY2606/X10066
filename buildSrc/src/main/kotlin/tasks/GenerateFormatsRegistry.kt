package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Generates the registry of built-in [io.github.optimumcode.json.schema.FormatValidator]s
 * from the Kotlin sources that define them.
 *
 * The registry is the single place that wires each JSON Schema `format` keyword
 * value to its implementation. The generation is deterministic:
 * the result depends only on the contents of [formatsSourceDir].
 * Keeping the generated file checked in allows a cheap verification task to fail
 * the build when the committed registry drifts from the sources
 * (a new validator, a removed one or a renamed format).
 */
abstract class GenerateFormatsRegistry : DefaultTask() {
  @get:InputDirectory
  abstract val formatsSourceDir: DirectoryProperty

  @get:OutputFile
  abstract val registryFile: RegularFileProperty

  /**
   * The verify instance reads the committed registry but never writes it,
   * so declaring it as a task output would falsely make compile tasks depend on
   * the verification task. Marked internal for the verify configuration.
   */
  @get:Internal
  abstract val expectedRegistryFile: RegularFileProperty

  /**
   * When `true` the task does not modify [registryFile].
   * It regenerates the content in memory and fails if it differs from the committed file.
   */
  @get:Input
  abstract val verify: Property<Boolean>

  init {
    group = "generation"
    verify.convention(false)
  }

  @TaskAction
  protected fun generate() {
    val entries = collectEntries()
    val generated = render(entries)
    val verifyOnly = verify.get()
    val output =
      if (verifyOnly) {
        expectedRegistryFile.orNull?.asFile
          ?: throw GradleException("expectedRegistryFile must be set for verification")
      } else {
        registryFile.get().asFile
      }
    if (!output.exists()) {
      if (verifyOnly) {
        throw GradleException(
          "generated format registry is missing: ${project.relativePath(output)} (run generateFormatsRegistry)",
        )
      }
      output.parentFile.mkdirs()
      output.writeText(generated)
      logger.lifecycle("Generated format registry {}", project.relativePath(output))
      return
    }
    val existing = output.readText()
    if (existing != generated) {
      if (verifyOnly) {
        throw GradleException(
          buildString {
            append("format registry is stale: ")
            append(project.relativePath(output))
            append(" does not match the validators declared in ")
            append(project.relativePath(formatsSourceDir.get().asFile))
            appendLine()
            appendLine("Regenerate it with ./gradlew generateFormatsRegistry and commit the result.")
            appendLine("Expected content:")
            append(generated)
          },
        )
      }
      output.writeText(generated)
      logger.lifecycle("Updated format registry {}", project.relativePath(output))
      return
    }
    logger.lifecycle("Format registry is up-to-date: {}", project.relativePath(output))
  }

  private fun collectEntries(): List<RegistryEntry> {
    val sourceDir = formatsSourceDir.get().asFile
    val sourceFiles =
      sourceDir.listFiles { file ->
        file.isFile && file.extension == "kt"
      }?.sortedBy { it.name }
        ?: throw GradleException("cannot list format validators directory: ${project.relativePath(sourceDir)}")

    val entries = mutableListOf<RegistryEntry>()
    for (sourceFile in sourceFiles) {
      val text = sourceFile.readText()
      val match = OBJECT_DECLARATION.find(text) ?: continue
      val className = match.groupValues[1]
      if (!className.endsWith("FormatValidator") || className.startsWith("Abstract")) {
        continue
      }
      val formatName = FORMAT_NAME_OVERRIDES[className] ?: className.toFormatName()
      entries.add(RegistryEntry(formatName, className))
    }

    val duplicates = entries.groupingBy { it.formatName }.eachCount().filterValues { it > 1 }
    require(duplicates.isEmpty()) {
      "duplicate format names generated from validators: $duplicates"
    }
    require(entries.isNotEmpty()) {
      "no format validators found in ${project.relativePath(sourceDir)}: the directory pattern is broken"
    }
    return entries.sortedBy { it.formatName }
  }

  private fun render(entries: List<RegistryEntry>): String =
    buildString {
      appendLine(FILE_HEADER)
      appendLine("package io.github.optimumcode.json.schema.internal.formats")
      appendLine()
      appendLine("import io.github.optimumcode.json.schema.FormatValidator")
      appendLine()
      appendLine("/**")
      appendLine(" * Maps JSON Schema `format` keyword values to their built-in validators.")
      appendLine(" *")
      appendLine(" * This file is generated by the `generateFormatsRegistry` Gradle task.")
      appendLine(" * Do not edit it manually: add a validator object to this package and regenerate.")
      appendLine(" */")
      appendLine("internal object GeneratedFormatsRegistry {")
      appendLine("  val formats: Map<String, FormatValidator> =")
      appendLine("    mapOf(")
      entries.forEach { entry ->
        append("      \"")
        append(entry.formatName)
        append('"')
        append(" to ")
        append(entry.className)
        appendLine(",")
      }
      appendLine("    )")
      append("}")
    }

  private data class RegistryEntry(
    val formatName: String,
    val className: String,
  )

  private companion object {
    const val FILE_HEADER = "// Generated by generateFormatsRegistry. DO NOT EDIT MANUALLY."
    val OBJECT_DECLARATION = Regex("""(?m)^\s*(?:internal\s+)?object\s+([A-Za-z0-9_]+)\b""")

    /**
     * Format names that cannot be derived from the class name by the generic
     * PascalCase to kebab-case conversion (the `V4`/`V6` tokens).
     */
    val FORMAT_NAME_OVERRIDES =
      mapOf(
        "IpV4FormatValidator" to "ipv4",
        "IpV6FormatValidator" to "ipv6",
      )

    /**
     * Converts a PascalCase validator class name (without the `FormatValidator` suffix)
     * into a kebab-case JSON Schema format name.
     * A boundary is inserted before an upper-case letter following a lower-case one,
     * and before the last upper-case letter of an acronym run followed by a lower-case one.
     * The irregular `V4`/`V6` tokens are handled explicitly via [FORMAT_NAME_OVERRIDES].
     */
    fun String.toFormatName(): String {
      val simpleName = removeSuffix("FormatValidator")
      val result = StringBuilder()
      for (index in simpleName.indices) {
        val current = simpleName[index]
        val previous = simpleName.getOrNull(index - 1)
        val next = simpleName.getOrNull(index + 1)
        val boundary =
          previous != null &&
            current.isUpperCase() &&
            (
              previous.isLowerCase() ||
                previous.isDigit() ||
                (next != null && next.isLowerCase() && !previous.isUpperCase())
            )
        if (boundary) {
          result.append('-')
        }
        result.append(current.lowercaseChar())
      }
      return result.toString()
    }
  }
}
