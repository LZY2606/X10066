package io.github.optimumcode.json.schema.gate

import kotlinx.serialization.Serializable

@Serializable
internal data class GateReport(
  val target: String,
  val commonFixtures: List<String>,
  val targetSpecificFixtures: List<String>,
  /**
   * Maps a JSON Schema draft id to whether an invalid value for a known format
   * fails validation by default (i.e. format is an assertion, not an annotation only).
   * The multiplatform gate fails when these defaults differ between targets.
   */
  val formatAssertionByDefault: Map<String, Boolean>,
)
