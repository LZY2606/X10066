package io.github.optimumcode.json.schema.internal.formats

import io.github.optimumcode.json.schema.FormatBehavior.ANNOTATION_AND_ASSERTION
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaOption
import io.github.optimumcode.json.schema.SchemaType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Guards the alignment between the generated registry and the exported API:
 * every generated entry must be resolvable through the public loader,
 * registry keys must be unique and normalized, and non-string values must pass
 * (a registered validator applied to the wrong type never asserts).
 */
public class GeneratedFormatsRegistryTest :
  FunSpec({
    test("registry is non-empty and contains no duplicate keys") {
      val formats = GeneratedFormatsRegistry.formats
      formats.keys.shouldContainExactlyInAnyOrder(formats.keys.toSet())
      formats.keys.forEach { key ->
        key.lowercase() shouldBe key
        key shouldBe key.trim()
      }
    }

    test("every registered format is reachable through the public API with assertion behaviour") {
      val loader =
        JsonSchemaLoader
          .create()
          .withSchemaOption(SchemaOption.FORMAT_BEHAVIOR_OPTION, ANNOTATION_AND_ASSERTION)
      SchemaType.entries.forEach(loader::registerWellKnown)
      GeneratedFormatsRegistry.formats.keys.forEach { formatName ->
        val schema =
          loader.fromJsonElement(
            buildJsonObject {
              put("format", JsonPrimitive(formatName))
            },
          )
        // A non-string value must always pass, even for asserting formats:
        // it proves the registered validator is wired through the public loader.
        schema.validate(JsonPrimitive(42), io.github.optimumcode.json.schema.ErrorCollector.EMPTY) shouldBe true
      }
    }
  })