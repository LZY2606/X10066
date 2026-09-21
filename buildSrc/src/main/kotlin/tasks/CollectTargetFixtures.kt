package tasks

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Collects the gate fixtures visible to one Kotlin target and writes a JSON report.
 * Fixtures are split into `common` (must be collected by every target),
 * `optional` (collected by every target but not required by the specification)
 * and `target-specific/<category>` (collected only by targets of that category).
 */
abstract class CollectTargetFixtures : DefaultTask() {
  @get:Input
  abstract val targetName: Property<String>

  @get:Input
  abstract val targetCategory: Property<String>

  @get:InputDirectory
  abstract val fixturesDir: DirectoryProperty

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description = "Collects gate fixtures visible to a single target"
  }

  @TaskAction
  fun collect() {
    val root: File = fixturesDir.get().asFile
    val common = listJsonFiles(root, "common")
    val optional = listJsonFiles(root, "optional")
    val targetSpecific = listJsonFiles(root, "target-specific/${targetCategory.get()}")
    val report =
      linkedMapOf(
        "target" to targetName.get(),
        "category" to targetCategory.get(),
        "common" to common,
        "optional" to optional,
        "targetSpecific" to targetSpecific,
      )
    val output = reportFile.get().asFile
    output.writeText(JsonOutput.toJson(report))
    logger.lifecycle(
      "target ${targetName.get()}: collected ${common.size} common, " +
        "${optional.size} optional, ${targetSpecific.size} target-specific fixtures",
    )
  }

  private fun listJsonFiles(
    root: File,
    subDir: String,
  ): List<String> {
    val dir = root.resolve(subDir)
    if (!dir.isDirectory) {
      return emptyList()
    }
    return dir.walkTopDown()
      .filter { it.isFile && it.extension == "json" }
      .map { it.relativeTo(root).invariantSeparatorsPath }
      .sorted()
      .toList()
  }
}