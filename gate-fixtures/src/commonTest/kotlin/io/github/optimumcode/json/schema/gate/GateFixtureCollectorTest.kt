package io.github.optimumcode.json.schema.gate

import io.github.optimumcode.json.schema.ErrorCollector
import io.github.optimumcode.json.schema.FormatBehavior.ANNOTATION_AND_ASSERTION
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaOption
import io.github.optimumcode.json.schema.SchemaType
import io.github.optimumcode.json.schema.ValidationError
import io.github.optimumcode.json.schema.gate.generated.common.COMMON_GATE_FIXTURES
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val REPORT_ENV = "GATE_FIXTURE_REPORT"
private const val INVALID_FORMAT_VALUE = "definitely-not-an-ipv4"

private val json = Json

internal class GateFixtureCollectorTest : FunSpec() {
  init {
    val collectedCommon = mutableListOf<String>()

    COMMON_GATE_FIXTURES.forEach { (fixtureId, fixtureJson) ->
      test("collects common fixture '$fixtureId' on ${currentTargetFamily.name}") {
        Fixture.parse(fixtureId, fixtureJson).runCases()
        collectedCommon += fixtureId
      }
    }

    targetSpecificGateFixtures().forEach { (fixtureId, fixtureJson) ->
      test("collects target-specific fixture '$fixtureId' on ${currentTargetFamily.name}") {
        Fixture.parse(fixtureId, fixtureJson).runCases()
      }
    }

    test("common fixture collection is complete on ${currentTargetFamily.name}") {
      collectedCommon.shouldContainExactlyInAnyOrder(COMMON_GATE_FIXTURES.keys)
      // Registered last: on success every common fixture above has been collected.
      // The report is the artifact the cross-target gate task aggregates.
      val reportPath = readGateEnv(REPORT_ENV)
        ?: fail("missing $REPORT_ENV environment variable: gate cannot report collected fixtures")
      val report =
        GateReport(
          target = currentTargetFamily.name,
          commonFixtures = collectedCommon.sorted(),
          targetSpecificFixtures = targetSpecificGateFixtures().keys.sorted(),
          formatAssertionByDefault = probeFormatDefaults(),
        )
      writeGateReport(reportPath, json.encodeToString(GateReport.serializer(), report))
    }
  }
}

private class FixtureCase(
  val data: JsonElement,
  val valid: Boolean,
)

private class Fixture(
  val id: String,
  val schema: JsonElement,
  val cases: List<FixtureCase>,
) {
  fun runCases() {
    val loader =
      JsonSchemaLoader
        .create()
        .withSchemaOption(SchemaOption.FORMAT_BEHAVIOR_OPTION, ANNOTATION_AND_ASSERTION)
    SchemaType.entries.forEach(loader::registerWellKnown)
    val jsonSchema = loader.fromJsonElement(schema)
    cases.forEach { case ->
      val errors = mutableListOf<ValidationError>()
      val valid = jsonSchema.validate(case.data, errors::add)
      if (valid != case.valid) {
        fail(
          buildString {
            append("fixture '")
            append(id)
            append("': expected valid=")
            append(case.valid)
            append(" but was ")
            append(valid)
            append(" for data ")
            append(case.data)
            if (errors.isNotEmpty()) {
              append(" (errors: ")
              append(errors.joinToString { it.message })
              append(")")
            }
          },
        )
      }
    }
  }

  companion object {
    fun parse(
      id: String,
      content: String,
    ): Fixture {
      val root = json.parseToJsonElement(content).jsonObject
      val parsedId = root.getValue("id").jsonPrimitive.content
      require(parsedId == id) { "fixture file key '$id' does not match embedded id '$parsedId'" }
      val cases =
        root.getValue("cases").jsonArray.map { element ->
          val case = element.jsonObject
          FixtureCase(
            data = case.getValue("data"),
            valid = (case.getValue("valid") as JsonPrimitive).booleanOrNull
              ?: error("case 'valid' must be a boolean"),
          )
        }
      require(cases.isNotEmpty()) { "fixture '$id' declares no cases" }
      return Fixture(id, root.getValue("schema"), cases)
    }
  }
}

/**
 * Probes the default format behaviour for every well-known draft through the public API only.
 * Drafts up to 2019-09 assert formats by default; draft 2020-12 only annotates.
 * A silent flip of any of these defaults is a semantic regression the gate must catch.
 */
private fun probeFormatDefaults(): Map<String, Boolean> {
  val loader = JsonSchemaLoader.create()
  SchemaType.entries.forEach(loader::registerWellKnown)
  return SchemaType.entries.associate { schemaType ->
    val schema =
      loader.fromJsonElement(
        JsonObject(mapOf("format" to JsonPrimitive("ipv4"))),
        schemaType,
      )
    val valid = schema.validate(JsonPrimitive(INVALID_FORMAT_VALUE), ErrorCollector.EMPTY)
    schemaType.name to !valid
  }
}
