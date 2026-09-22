package tasks

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Verifies that every common specification fixture is collected with the same
 * number of test cases on every target that can run on this host, that no
 * common fixture collects zero tests on any target, and reports targets and
 * target-specific fixtures that are skipped on this host separately.
 *
 * Fixture layout (discovered, never matched by name):
 *   <fixturesDir>/<draft>/ ... json files                       common fixtures
 *   <fixturesDir>/<draft>/optional/ ... json files              common optional fixtures
 *   <fixturesDir>/<draft>/optional/format/ ... json files       common format fixtures
 *   <fixturesDir>/<draft>/target-specific/<family>/ ... json    per-family fixtures
 */
abstract class VerifyFixtureParity : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val fixturesDir: DirectoryProperty

  @get:Input
  abstract val targets: ListProperty<String>

  @get:Input
  abstract val hostOs: Property<String>

  @get:Input
  abstract val hostArch: Property<String>

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  init {
    group = "verification"
    description = "Checks common fixtures collect identical test counts on every host-runnable target"
    hostOs.convention(System.getProperty("os.name"))
    hostArch.convention(System.getProperty("os.arch"))
  }

  @TaskAction
  fun verify() {
    val problems = mutableListOf<String>()
    val root = fixturesDir.get().asFile
    require(root.isDirectory) { "fixtures directory ${root.absolutePath} does not exist" }

    val fixtures = discoverFixtures(root)
    val commonFixtures = fixtures.filter { it.family == null }
    if (commonFixtures.isEmpty()) {
      problems +=
        "no common fixtures discovered under ${root.rel()}: " +
          "the fixture parity gate cannot verify anything and must not pass silently"
    }

    val testCounts: Map<Fixture, Int> =
      fixtures.associateWith { fixture ->
        countTests(fixture.file)
      }

    val allTargets = targets.get()
    if (allTargets.isEmpty()) {
      problems += "no Kotlin targets were provided to the fixture parity gate"
    }
    val hostTarget = hostNativeTarget()
    val runnableTargets = mutableListOf<String>()
    val skippedTargets = mutableListOf<Pair<String, String>>()
    for (target in allTargets) {
      val skipReason = skipReason(target, hostTarget)
      if (skipReason == null) {
        runnableTargets += target
      } else {
        skippedTargets += target to skipReason
      }
    }
    if (runnableTargets.isEmpty()) {
      problems += "no runnable targets on host ${hostOs.get()}/${hostArch.get()}: parity cannot be verified"
    }

    // per-target collection: common fixtures plus the target-specific fixtures of its family
    val collection: Map<String, List<Pair<Fixture, Int>>> =
      runnableTargets.associateWith { target ->
        val family = familyOf(target)
        fixtures
          .filter { it.family == null || it.family == family }
          .map { it to (testCounts[it] ?: 0) }
      }

    for ((target, collected) in collection) {
      for ((fixture, count) in collected) {
        if (count == 0) {
          problems +=
            "fixture ${fixture.file.rel()} collected zero tests on target '$target': " +
              "zero collection is rejected"
        }
      }
    }

    for (fixture in commonFixtures) {
      val countsPerTarget =
        runnableTargets.associateWith { target ->
          collection.getValue(target).first { it.first == fixture }.second
        }
      val distinct = countsPerTarget.values.distinct()
      if (distinct.size > 1) {
        val detail = countsPerTarget.entries.joinToString(", ") { (t, c) -> "$t=$c" }
        problems +=
          "common fixture ${fixture.file.rel()} collects different test counts per target ($detail): " +
            "collection must be identical on every target"
      }
    }

    writeReport(collection, skippedTargets, fixtures, problems)
    if (problems.isNotEmpty()) {
      throw GradleException(
        problems.joinToString(
          prefix = "fixture parity verification failed with ${problems.size} problem(s):\n - ",
          separator = "\n - ",
        ),
      )
    }
    logger.lifecycle(
      "fixture parity OK: {} common fixtures, {} runnable targets, {} skipped targets",
      commonFixtures.size,
      runnableTargets.size,
      skippedTargets.size,
    )
  }

  private fun discoverFixtures(root: File): List<Fixture> {
    val result = mutableListOf<Fixture>()
    root.listFiles()
      ?.filter { it.isDirectory }
      ?.sortedBy { it.name }
      ?.forEach { draftDir ->
        draftDir.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedBy { it.name }
          ?.forEach { result += Fixture(kind = "common", family = null, file = it) }
        collectJson(draftDir.resolve("optional"), depthPrefix = "optional", result, family = null)
        collectJson(draftDir.resolve("optional/format"), depthPrefix = "optional/format", result, family = null)
        draftDir.resolve("target-specific").listFiles()
          ?.filter { it.isDirectory }
          ?.sortedBy { it.name }
          ?.forEach { familyDir ->
            collectJson(familyDir, depthPrefix = "target-specific/${familyDir.name}", result, family = familyDir.name)
          }
      }
    return result
  }

  private fun collectJson(
    dir: File,
    depthPrefix: String,
    result: MutableList<Fixture>,
    family: String?,
  ) {
    dir.listFiles()
      ?.filter { it.isFile && it.extension == "json" }
      ?.sortedBy { it.name }
      ?.forEach { result += Fixture(kind = depthPrefix, family = family, file = it) }
  }

  private fun countTests(file: File): Int {
    val parsed =
      try {
        JsonSlurper().parse(file)
      } catch (ex: Exception) {
        throw GradleException("cannot parse fixture ${file.rel()}: ${ex.message}", ex)
      }
    if (parsed !is List<*>) {
      throw GradleException("fixture ${file.rel()} is not a JSON array of test suites")
    }
    return parsed.sumOf { suite ->
      val tests = (suite as? Map<*, *>)?.get("tests") as? List<*>
        ?: throw GradleException("fixture ${file.rel()} contains a suite without a 'tests' array")
      tests.size
    }
  }

  private fun skipReason(
    target: String,
    hostTarget: String?,
  ): String? =
    when {
      target == "jvm" || target == "js" || target.startsWith("wasm") -> null
      target == hostTarget -> null
      target.startsWith("ios") -> "requires an Apple device or simulator runtime"
      else -> "no runner for target '$target' on host ${hostOs.get()}/${hostArch.get()}"
    }

  private fun hostNativeTarget(): String? {
    val os = hostOs.get().lowercase()
    val arch = hostArch.get().lowercase()
    val aarch64 = arch == "aarch64" || arch == "arm64"
    return when {
      "mac" in os || "darwin" in os -> if (aarch64) "macosArm64" else "macosX64"
      "linux" in os -> if (aarch64) "linuxArm64" else "linuxX64"
      "windows" in os -> "mingwX64"
      else -> null
    }
  }

  private fun writeReport(
    collection: Map<String, List<Pair<Fixture, Int>>>,
    skippedTargets: List<Pair<String, String>>,
    fixtures: List<Fixture>,
    problems: List<String>,
  ) {
    val out = reportFile.get().asFile
    out.parentFile.mkdirs()
    out.printWriter().use { writer ->
      writer.println("fixture parity verification report")
      writer.println("host: ${hostOs.get()}/${hostArch.get()}")
      writer.println("collected fixtures: ${fixtures.size}")
      for ((target, collected) in collection.toSortedMap()) {
        val total = collected.sumOf { it.second }
        writer.println("target $target (family ${familyOf(target)}): ${collected.size} fixtures, $total tests")
        for ((fixture, count) in collected) {
          writer.println("  ${fixture.file.rel()} -> $count test(s)")
        }
      }
      writer.println("skipped targets:")
      for ((target, reason) in skippedTargets) {
        writer.println("  $target: $reason")
      }
      val skippedFamilies =
        fixtures.mapNotNull { it.family }.distinct()
          .filter { family -> collection.keys.none { familyOf(it) == family } }
      for (family in skippedFamilies) {
        writer.println("  target-specific fixtures for family '$family' are not collected on this host")
      }
      if (problems.isEmpty()) {
        writer.println("result: OK")
      } else {
        writer.println("result: FAILED")
        problems.forEach { writer.println("  - $it") }
      }
    }
  }

  private fun File.rel(): String = relativeTo(project.rootDir).invariantSeparatorsPath

  private data class Fixture(
    val kind: String,
    val family: String?,
    val file: File,
  )

  companion object {
    fun familyOf(target: String): String =
      when {
        target == "jvm" -> "jvm"
        target == "js" -> "js"
        target.startsWith("wasm") -> "wasmJs"
        else -> "native"
      }
  }
}
