package io.github.optimumcode.json.schema.gates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single test case inside a fixture group. Mirrors the JSON-Schema-Test-Suite format.
 */
@Serializable
public data class SuiteTest(
  val description: String,
  val data: JsonElement,
  val valid: Boolean,
)

/**
 * A group of tests that share one schema. Mirrors the JSON-Schema-Test-Suite format.
 */
@Serializable
public data class SuiteGroup(
  val description: String,
  val schema: JsonElement,
  val tests: List<SuiteTest>,
)

/**
 * One entry of the generated manifest. Describes a fixture file that must be collected
 * by every target. The [sha256] is computed on the JVM when the manifest is (re)generated;
 * other targets only compare it against the committed value.
 */
@Serializable
public data class FixtureExpectation(
  val id: String,
  val sha256: String,
  val suites: Int,
  val tests: Int,
)

/**
 * Declared skips for one target. Loaded from `spec-fixtures/target-specific/<target>.json`.
 */
@Serializable
public data class TargetSkips(
  val reason: String,
  val skips: Map<String, List<String>> = emptyMap(),
)

/**
 * What one target collected and executed for one fixture.
 */
@Serializable
public data class FixtureCollection(
  val id: String,
  val collected: Int,
  val executed: Int,
  val skipped: List<String> = emptyList(),
)

/**
 * The report one target writes after collecting and executing the fixtures.
 */
@Serializable
public data class CollectionReport(
  val target: String,
  val manifestRevision: String,
  val fixtures: List<FixtureCollection>,
)

/**
 * Expected default format behavior for one draft.
 */
@Serializable
public data class DraftFormatDefault(
  val draft: String,
  val configFile: String,
  val expectedDefault: String,
)

/**
 * Expectations for the format-registry gate, loaded from
 * `spec-fixtures/format-registry-expectations.json`.
 */
@Serializable
public data class FormatRegistryExpectations(
  val draftFormatDefaults: List<DraftFormatDefault>,
  val exportedApiFragments: List<String>,
)

/**
 * A single gate violation. [check] identifies the gate, [message] carries the
 * diagnosable context (fixture ids, targets, files) needed to fix the problem.
 */
public data class GateViolation(
  val check: String,
  val message: String,
)
