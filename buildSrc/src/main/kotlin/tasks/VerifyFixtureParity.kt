package tasks

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Compares per-target fixture collection reports and enforces that every common
 * (and optional) fixture is collected by every available target. Target-specific
 * fixtures are expected to differ; the resulting skips are reported separately
 * instead of failing the build.
 */
abstract class VerifyFixtureParity : DefaultTask() {
  @get:InputFiles
  abstract val targetReports: ConfigurableFileCollection

  @get:OutputFile
  abstract val skipReport: RegularFileProperty

  init {
    group = "verification"
    description = "Verifies common gate fixtures are collected consistently on all targets"
  }

  private data class TargetReport(
    val target: String,
    val category: String,
    val common: Set<String>,
    val optional: Set<String>,
    val targetSpecific: Set<String>,
  )

  @TaskAction
  fun verify() {
    val reports =
      targetReports.files
        .filter { it.isFile }
        .sortedBy { it.name }
        .map(::parseReport)
    require(reports.isNotEmpty()) { "no target fixture reports were collected" }

    val problems = mutableListOf<String>()
    verifyCategoryParity("common", reports.associate { it.target to it.common }, problems)
    verifyCategoryParity("optional", reports.associate { it.target to it.optional }, problems)

    val skipLines = computeTargetSpecificSkips(reports)
    skipReport.get().asFile.writeText(skipLines.joinToString("\n", postfix = if (skipLines.isEmpty()) "" else "\n"))
    skipLines.forEach { logger.lifecycle(it) }

    if (problems.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("fixture parity verification failed:")
          problems.forEach { appendLine("  - $it") }
        },
      )
    }
    logger.lifecycle(
      "fixture parity OK for ${reports.size} targets " +
        "(${reports.first().common.size} common fixtures each); " +
        "${skipLines.size} target-specific skips reported to ${skipReport.get().asFile}",
    )
  }

  private fun parseReport(file: File): TargetReport {
    val parsed: Any? = JsonSlurper().parse(file)
    require(parsed is Map<*, *>) { "fixture report $file must contain a JSON object" }
    fun strings(key: String): Set<String> =
      (parsed[key] as? List<*>)?.filterIsInstance<String>()?.toSet()
        ?: throw IllegalArgumentException("fixture report $file does not contain a '$key' array")
    return TargetReport(
      target = parsed["target"] as? String
        ?: throw IllegalArgumentException("fixture report $file does not contain a 'target'"),
      category = parsed["category"] as? String
        ?: throw IllegalArgumentException("fixture report $file does not contain a 'category'"),
      common = strings("common"),
      optional = strings("optional"),
      targetSpecific = strings("targetSpecific"),
    )
  }

  private fun verifyCategoryParity(
    categoryName: String,
    collectedByTarget: Map<String, Set<String>>,
    problems: MutableList<String>,
  ) {
    for ((target, fixtures) in collectedByTarget) {
      if (fixtures.isEmpty()) {
        problems += "target '$target' collected 0 $categoryName fixtures"
      }
    }
    val allFixtures = collectedByTarget.values.flatten().toSortedSet()
    for (fixture in allFixtures) {
      val missingOn = collectedByTarget.filterValues { fixture !in it }.keys.sorted()
      if (missingOn.isNotEmpty()) {
        problems += "$categoryName fixture '$fixture' is not collected on targets: ${missingOn.joinToString(", ")}"
      }
    }
  }

  private fun computeTargetSpecificSkips(reports: List<TargetReport>): List<String> {
    val allSpecific = reports.flatMap { it.targetSpecific }.toSortedSet()
    val lines = mutableListOf<String>()
    for (fixture in allSpecific) {
      val providedBy = reports.filter { fixture in it.targetSpecific }
      val skippedBy = reports.filter { fixture !in it.targetSpecific }
      if (skippedBy.isNotEmpty()) {
        lines +=
          "target-specific fixture '$fixture' " +
          "(provided by ${providedBy.joinToString(", ") { it.target }}) " +
          "is skipped on ${skippedBy.joinToString(", ") { it.target }}"
      }
    }
    return lines
  }
}