package io.github.optimumcode.json.schema.suite.gate

import io.github.optimumcode.json.schema.ErrorCollector
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaType
import io.github.optimumcode.json.schema.suite.fileSystem
import io.kotest.assertions.withClue
import io.kotest.common.env
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
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
 * Category of the current target used to pick target-specific gate fixtures.
 */
internal expect val gateTargetCategory: String

private const val GATE_FIXTURES_DIR_ENV_VAR = "GATE_FIXTURES_DIR"
private val FIXTURES_DIR_FROM_PROJECT: Path = "fixtures".toPath()
private val FIXTURES_DIR_FROM_ROOT: Path = "test-suites".toPath() / "fixtures"

/**
 * Executes the small committed gate fixtures on every target.
 *
 * Unlike the upstream JSON-Schema-Test-Suite (a git submodule that may be absent
 * in offline environments) these fixtures live in this repository, so every target
 * must collect them. A target that collects zero common fixtures fails here and in
 * the `:test-suites:verifyFixtureParity` Gradle gate.
 */
class GateFixtureTest : FunSpec() {
  init {
    val fs = fileSystem()
    val fixturesDir = resolveFixturesDir(fs)
    var commonTestCount = 0
    var optionalTestCount = 0
    var targetSpecificTestCount = 0
    executeFixtureDir(fs, fixturesDir / "common") { commonTestCount += 1 }
    executeFixtureDir(fs, fixturesDir / "optional") { optionalTestCount += 1 }
    val targetSpecificDir = fixturesDir / "target-specific" / gateTargetCategory
    if (fs.exists(targetSpecificDir)) {
      executeFixtureDir(fs, targetSpecificDir) { targetSpecificTestCount += 1 }
    }

    test("common gate fixtures are collected on this target") {
      commonTestCount shouldBeGreaterThan 0
    }
    test("optional gate fixtures are collected on this target") {
      optionalTestCount shouldBeGreaterThan 0
    }
    test("target-specific gate fixtures are collected for category $gateTargetCategory") {
      targetSpecificTestCount shouldBeGreaterThan 0
    }
  }
}

private fun resolveFixturesDir(fs: FileSystem): Path {
  val fromEnv = env(GATE_FIXTURES_DIR_ENV_VAR)?.toPath()
  val candidates = listOfNotNull(fromEnv, FIXTURES_DIR_FROM_PROJECT, FIXTURES_DIR_FROM_ROOT)
  return candidates.firstOrNull(fs::exists)
    ?: error(
      "cannot locate gate fixtures directory (checked: ${candidates.joinToString()}, " +
        "current dir: ${fs.canonicalize(".".toPath())}). " +
        "Set $GATE_FIXTURES_DIR_ENV_VAR to point at test-suites/fixtures",
    )
}

private fun FunSpec.executeFixtureDir(
  fs: FileSystem,
  dir: Path,
  onTestRegistered: () -> Unit = {},
) {
  require(fs.exists(dir)) { "gate fixtures directory $dir does not exist" }
  fs.listRecursively(dir)
    .filter { fs.metadata(it).isRegularFile && it.name.endsWith(".json") }
    .sortedBy { it.toString() }
    .forEach { fixtureFile ->
      val draftDir = fixtureFile.parent?.name
      val schemaType =
        SchemaType.entries.firstOrNull {
          it.name.replace("_", "").equals(draftDir?.replace("-", ""), ignoreCase = true)
        } ?: error("cannot map draft directory '$draftDir' of fixture $fixtureFile to a known SchemaType")
      val suites: List<GateFixtureSuite> = loadFixtureFile(fs, fixtureFile)
      suites.forEachIndexed { suiteIndex, suite ->
        suite.tests.forEachIndexed { testIndex, fixtureTest ->
          onTestRegistered()
          test("gate fixture ${fixtureFile.name} suite $suiteIndex test $testIndex") {
            withClue(listOf(suite.description, suite.schema, fixtureTest.description, fixtureTest.data)) {
              val schema = JsonSchemaLoader.create().fromJsonElement(suite.schema, schemaType)
              schema.validate(fixtureTest.data, ErrorCollector.EMPTY) shouldBe fixtureTest.valid
            }
          }
        }
      }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun loadFixtureFile(
  fs: FileSystem,
  fixtureFile: Path,
): List<GateFixtureSuite> =
  fs.openReadOnly(fixtureFile).use { handle ->
    handle.source().use {
      Json.decodeFromBufferedSource(ListSerializer(GateFixtureSuite.serializer()), it.buffer())
    }
  }

@Serializable
private class GateFixtureSuite(
  val description: String,
  val schema: JsonElement,
  val tests: List<GateFixtureCase>,
)

@Serializable
private class GateFixtureCase(
  val description: String,
  val data: JsonElement,
  val valid: Boolean,
)
