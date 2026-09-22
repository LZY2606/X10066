package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Aggregates the JSON reports emitted by the gate fixture tests on every target
 * and enforces the multiplatform invariants:
 *
 * 1. every expected target produced a report and collected the same,
 *    non-empty set of common fixtures;
 * 2. the default format behaviour is identical across targets;
 * 3. target-specific fixtures are listed separately (they are reported,
 *    not enforced across targets);
 * 4. a target that collected zero common fixtures fails the build.
 *
 * Sub-command failures (a missing test binary, a crash) propagate as missing reports.
 */
abstract class VerifyGateReports : DefaultTask() {
  @get:InputFiles
  abstract val reportFiles: ConfigurableFileCollection

  /** Targets that were executed on this host and must each have produced a report. */
  @get:Input
  abstract val expectedTargets: ListProperty<String>

  @get:OutputFile
  abstract val summaryFile: RegularFileProperty

  init {
    group = "verification"
    description = "Verifies common gate fixtures are collected identically on every target"
  }

  @TaskAction
  protected fun verify() {
    val present =
      reportFiles.files.associate { file ->
        file.nameWithoutExtension to JSONObject(file.readText())
      }

    val missing = expectedTargets.get().filter { it !in present }
    if (missing.isNotEmpty()) {
      throw GradleException(
        "missing gate reports for targets: $missing; " +
          "the corresponding test task did not run, crashed or collected zero fixtures",
      )
    }

    val byTarget = expectedTargets.get().associateWith { target -> present.getValue(target) }
    val failures = mutableListOf<String>()

    fun JSONObject.commonFixtureList(): Set<String> =
      getJSONArray("commonFixtures").toStringList().toSet()

    // Zero collection is a hard failure: it would hide every fixture silently.
    byTarget.forEach { (target, report) ->
      if (report.commonFixtureList().isEmpty()) {
        failures += "target '$target' collected zero common fixtures"
      }
    }

    val fixtureSets = byTarget.map { (target, report) -> target to report.commonFixtureList() }
    val referenceFixtures = fixtureSets.firstOrNull()?.second.orEmpty()
    fixtureSets.forEach { (target, fixtures) ->
      if (fixtures != referenceFixtures) {
        failures +=
          "target '$target' collected $fixtures but expected $referenceFixtures " +
            "(missing: ${referenceFixtures - fixtures}, extra: ${fixtures - referenceFixtures})"
      }
    }

    fun JSONObject.formatDefaults(): Map<String, Boolean> {
      val obj = getJSONObject("formatAssertionByDefault")
      return obj.keyList().associateWith { obj.getBoolean(it) }
    }

    val formatDefaults = byTarget.map { (target, report) -> target to report.formatDefaults() }
    val referenceDefaults = formatDefaults.firstOrNull()?.second.orEmpty()
    formatDefaults.forEach { (target, defaults) ->
      if (defaults != referenceDefaults) {
        failures += "target '$target' format defaults $defaults differ from $referenceDefaults"
      }
    }

    val targetSpecific =
      byTarget.map { (target, report) ->
        target to report.getJSONArray("targetSpecificFixtures").toStringList().sorted()
      }

    val summary =
      buildString {
        appendLine("Multiplatform gate fixture summary")
        appendLine("common fixtures (${referenceFixtures.size}): ${referenceFixtures.sorted()}")
        appendLine("format assertion by default: $referenceDefaults")
        targetSpecific.forEach { (target, fixtures) ->
          appendLine("target-specific on $target (reported, not required elsewhere): $fixtures")
        }
      }

    if (failures.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("multiplatform gate verification failed:")
          failures.forEach { appendLine(" - $it") }
          append(summary)
        },
      )
    }

    val output = summaryFile.get().asFile
    output.parentFile.mkdirs()
    output.writeText(summary)
    logger.lifecycle(summary)
  }

  private fun JSONArray.toStringList(): List<String> {
    val result = mutableListOf<String>()
    for (index in 0 until length()) {
      result.add(getString(index))
    }
    return result
  }

  private fun JSONObject.keyList(): List<String> {
    val result = mutableListOf<String>()
    keys().forEachRemaining { result.add(it) }
    return result
  }
}
