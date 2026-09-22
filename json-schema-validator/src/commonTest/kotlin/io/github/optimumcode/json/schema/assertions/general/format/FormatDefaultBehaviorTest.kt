package io.github.optimumcode.json.schema.assertions.general.format

import io.github.optimumcode.json.schema.ErrorCollector
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pins the per-draft DEFAULT format behaviour through the public API only.
 *
 * Drafts 4/6/7 treat `format` as an assertion by default;
 * drafts 2019-09 and 2020-12 only produce an annotation.
 * This difference is part of the JSON Schema specification history and must not
 * drift silently between targets or releases: the multiplatform gate also
 * compares this matrix across JVM/JS/wasmJs/native.
 */
class FormatDefaultBehaviorTest :
  FunSpec({
    val invalidIpV4 = JsonPrimitive("definitely-not-an-ipv4")

    SchemaType.entries.forEach { schemaType ->
      test("default format behaviour for $schemaType") {
        val loader = JsonSchemaLoader.create()
        loader.registerWellKnown(schemaType)
        val schema =
          loader.fromJsonElement(
            kotlinx.serialization.json.buildJsonObject {
              put("format", JsonPrimitive("ipv4"))
            },
            schemaType,
          )
        val expectedAssertion =
          when (schemaType) {
            SchemaType.DRAFT_4, SchemaType.DRAFT_6, SchemaType.DRAFT_7 -> true
            SchemaType.DRAFT_2019_09, SchemaType.DRAFT_2020_12 -> false
          }
        schema.validate(invalidIpV4, ErrorCollector.EMPTY) shouldBe !expectedAssertion
      }
    }
  })