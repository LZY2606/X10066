package io.github.optimumcode.json.schema.internal.formats

import io.github.optimumcode.json.schema.ErrorCollector
import io.github.optimumcode.json.schema.FormatBehavior.ANNOTATION_AND_ASSERTION
import io.github.optimumcode.json.schema.JsonSchemaLoader
import io.github.optimumcode.json.schema.SchemaOption
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive

/**
 * Verifies that the generated format registry is internally consistent and that
 * every registered format is actually wired into schema loading. Each format gets
 * its own test case so a broken entry can be located individually.
 */
class GeneratedFormatRegistryTest : FunSpec() {
  init {
    test("registry names are unique, sorted and lowercase kebab-case") {
      val names = GeneratedFormatRegistry.formatNames.toList()
      assertSoftly {
        names shouldBe names.sorted()
        names.toSet().size shouldBe names.size
        names.forEach { name ->
          Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(name) shouldBe true
        }
      }
    }

    test("formatNames and knownFormats keys are aligned") {
      GeneratedFormatRegistry.knownFormats.keys shouldBe GeneratedFormatRegistry.formatNames
    }

    test("every registered format has a wiring sample") {
      FORMAT_SAMPLES.keys shouldBe GeneratedFormatRegistry.formatNames
    }

    FORMAT_SAMPLES.forEach { (name, samples) ->
      test("format '$name' is wired into schema loading") {
        val schema =
          JsonSchemaLoader.create()
            .withSchemaOption(SchemaOption.FORMAT_BEHAVIOR_OPTION, ANNOTATION_AND_ASSERTION)
            .fromDefinition("""{ "format": "$name" }""")
        assertSoftly {
          schema.validate(JsonPrimitive(samples.valid), ErrorCollector.EMPTY) shouldBe true
          schema.validate(JsonPrimitive(samples.invalid), ErrorCollector.EMPTY) shouldBe false
        }
      }
    }
  }

  private data class FormatSamples(
    val valid: String,
    val invalid: String,
  )

  private companion object {
    private val FORMAT_SAMPLES: Map<String, FormatSamples> =
      mapOf(
        "date" to FormatSamples(valid = "2024-02-29", invalid = "2024-13-40"),
        "date-time" to FormatSamples(valid = "2024-02-29T12:42:54Z", invalid = "not-a-date-time"),
        "duration" to FormatSamples(valid = "PT1S", invalid = "not-a-duration"),
        "email" to FormatSamples(valid = "test@example.com", invalid = "not-an-email"),
        "hostname" to FormatSamples(valid = "hostname", invalid = "host..name"),
        "idn-email" to FormatSamples(valid = "test@example.com", invalid = "not-an-email"),
        "idn-hostname" to FormatSamples(valid = "hostname", invalid = "host..name"),
        "ipv4" to FormatSamples(valid = "127.0.0.1", invalid = "256.256.256.256"),
        "ipv6" to FormatSamples(valid = "::1", invalid = ":::1"),
        "iri" to FormatSamples(valid = "https://example.com/test", invalid = "http://exa mple.com"),
        "iri-reference" to FormatSamples(valid = "/localhost?query=5#fragment", invalid = "http://exa mple.com"),
        "json-pointer" to FormatSamples(valid = "/test/a", invalid = "no-leading-slash"),
        "regex" to FormatSamples(valid = "(?=test\\s)", invalid = "["),
        "relative-json-pointer" to FormatSamples(valid = "0", invalid = "abc"),
        "time" to FormatSamples(valid = "11:42:59Z", invalid = "25:61:61Z"),
        "uri" to FormatSamples(valid = "https://example.com", invalid = "http://exa mple.com"),
        "uri-reference" to FormatSamples(valid = "/localhost?query=5#fragment", invalid = "http://exa mple.com"),
        "uri-template" to
          FormatSamples(valid = "https://example.com/{test}", invalid = "https://example.com/{unclosed"),
        "uuid" to FormatSamples(valid = "f81d4fae-7dec-11d0-a765-00a0c91e6bf6", invalid = "not-a-uuid"),
      )
  }
}