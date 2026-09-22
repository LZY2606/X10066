package io.github.optimumcode.json.schema.suite.standalone

import io.github.optimumcode.json.schema.ErrorCollector
import io.github.optimumcode.json.schema.FormatBehavior.ANNOTATION_AND_ASSERTION
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaOption
import io.github.optimumcode.json.schema.SchemaType
import io.github.optimumcode.json.schema.suite.fileSystem
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.common.env
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Executes the small standalone fixtures from `verification/fixtures` on every target.
 *
 * The fixtures are discovered dynamically: no fixture file name is special-cased.
 * The spec fails when a common fixture collects zero tests on the current target,
 * and reports target-specific fixture families that are skipped on this target.
 */
internal class StandaloneFixtureGateTest : FunSpec({
  val fs = fileSystem()
  val fixturesRoot = resolveFixturesRoot(fs)
  val family = currentTargetFamily()

  val discovered = discoverFixtures(fs, fixturesRoot, family)

  if (discovered.skippedFamilies.isNotEmpty()) {
    println(
      "target-specific fixture families skipped on target family '$family': " +
        discovered.skippedFamilies.joinToString(),
    )
  }

  if (discovered.commonFiles.isEmpty()) {
    test("common fixture collection must not be empty") {
      fail(
        "no common fixtures were collected under $fixturesRoot on target family '$family': " +
          "zero collection of common fixtures is rejected",
      )
    }
  }

  val defaultLoader = createLoader(formatAssertions = false)
  val formatLoader = createLoader(formatAssertions = true)

  for (fixture in discovered.files) {
    val suites = loadSuites(fs, fixturesRoot, fixture.path)
    val totalTests = suites.sumOf { it.tests.size }
    if (totalTests == 0) {
      test("fixture ${fixture.displayPath} collects zero tests") {
        fail(
          "fixture ${fixture.displayPath} collected zero tests on target family '$family': " +
            "zero collection is rejected",
        )
      }
      continue
    }
    val loader = if (fixture.formatAssertions) formatLoader else defaultLoader
    var suiteIndex = -1
    for (suite in suites) {
      suiteIndex += 1
      var testIndex = -1
      for (case in suite.tests) {
        testIndex += 1
        test("${fixture.displayPath} at index $suiteIndex test $testIndex") {
          withClue(listOf(suite.description, suite.schema, case.description, case.data)) {
            val schema: JsonSchema = loader.fromJsonElement(suite.schema)
            schema.validate(case.data, ErrorCollector.EMPTY) shouldBe case.valid
          }
        }
      }
    }
  }
})

private const val FIXTURES_DIR_ENV_VAR: String = "VERIFICATION_FIXTURES_DIR"

private fun resolveFixturesRoot(fs: FileSystem): Path {
  val candidates =
    listOfNotNull(
      env(FIXTURES_DIR_ENV_VAR)?.toPath(),
      "verification/fixtures".toPath(),
      "../verification/fixtures".toPath(),
    )
  return candidates.firstOrNull(fs::exists)
    ?: error(
      "cannot locate the standalone fixtures directory; tried: " +
        candidates.joinToString() +
        " (current dir: ${fs.canonicalize(".".toPath())}, env: ${env(FIXTURES_DIR_ENV_VAR)})",
    )
}

private class DiscoveredFixtures(
  val files: List<FixtureFile>,
  val commonFiles: List<FixtureFile>,
  val skippedFamilies: List<String>,
)

private class FixtureFile(
  val path: Path,
  val displayPath: String,
  val formatAssertions: Boolean,
)

private fun discoverFixtures(
  fs: FileSystem,
  root: Path,
  family: String,
): DiscoveredFixtures {
  val files = mutableListOf<FixtureFile>()
  val skippedFamilies = mutableSetOf<String>()
  for (draftDir in fs.list(root).sorted().filter { fs.metadata(it).isDirectory }) {
    collectJsonFiles(fs, draftDir, root, formatAssertions = false, into = files)
    val optional = draftDir / "optional"
    if (fs.exists(optional)) {
      collectJsonFiles(fs, optional, root, formatAssertions = false, into = files)
      val format = optional / "format"
      if (fs.exists(format)) {
        collectJsonFiles(fs, format, root, formatAssertions = true, into = files)
      }
    }
    val targetSpecific = draftDir / "target-specific"
    if (fs.exists(targetSpecific)) {
      for (familyDir in fs.list(targetSpecific).sorted().filter { fs.metadata(it).isDirectory }) {
        if (familyDir.name == family) {
          collectJsonFiles(fs, familyDir, root, formatAssertions = false, into = files)
        } else {
          skippedFamilies += familyDir.name
        }
      }
    }
  }
  return DiscoveredFixtures(
    files = files,
    commonFiles = files.filter { "target-specific" !in it.displayPath },
    skippedFamilies = skippedFamilies.sorted(),
  )
}

private fun collectJsonFiles(
  fs: FileSystem,
  dir: Path,
  root: Path,
  formatAssertions: Boolean,
  into: MutableList<FixtureFile>,
) {
  for (file in fs.list(dir).sorted()) {
    if (fs.metadata(file).isDirectory || !file.name.endsWith(".json")) {
      continue
    }
    into +=
      FixtureFile(
        path = file,
        displayPath = file.toString().removePrefix("$root").trimStart('/', '\\'),
        formatAssertions = formatAssertions,
      )
  }
}

private fun createLoader(formatAssertions: Boolean): JsonSchemaLoader =
  JsonSchemaLoader
    .create()
    .apply {
      if (formatAssertions) {
        withSchemaOption(SchemaOption.FORMAT_BEHAVIOR_OPTION, ANNOTATION_AND_ASSERTION)
      }
      SchemaType.entries.forEach(::registerWellKnown)
    }

@OptIn(ExperimentalSerializationApi::class)
private fun loadSuites(
  fs: FileSystem,
  root: Path,
  file: Path,
): List<StandaloneSuite> =
  fs.openReadOnly(file).use { handle ->
    handle.source().use {
      Json.decodeFromBufferedSource(ListSerializer(StandaloneSuite.serializer()), it.buffer())
    }
  }

@Serializable
private class StandaloneSuite(
  val description: String,
  val schema: JsonElement,
  val tests: List<StandaloneCase>,
)

@Serializable
private class StandaloneCase(
  val description: String,
  val data: JsonElement,
  val valid: Boolean,
)

internal expect fun currentTargetFamily(): String
