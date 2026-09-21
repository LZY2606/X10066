@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import tasks.GenerateFormatRegistry
import tasks.VerifyFormatRegistry
import tasks.VerifyGeneratedSources

plugins {
  convention.kotlin
  convention.`multiplatform-lib`
  convention.`multiplatform-tests`
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kover)
  convention.publication
}

kotlin {
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  compilerOptions {
    freeCompilerArgs.add("-opt-in=io.github.optimumcode.json.schema.ExperimentalApi")
  }

  sourceSets {
    commonMain {
      dependencies {
        api(libs.kotlin.serialization.json)
        api(libs.uri)
        // When using approach like above you won't be able to add because block
        implementation(
          libs.kotlin.codepoints
            .get()
            .toString(),
        ) {
          because("simplifies work with unicode codepoints")
        }
        implementation(libs.normalize.get().toString()) {
          because("provides normalization required by IDN-hostname format")
        }
        implementation(libs.karacteristics)
      }
    }

    commonTest {
      dependencies {
        implementation(libs.kotest.assertions.core)
        implementation(libs.kotest.framework.engine)
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }
    jvmTest {
      dependencies {
        implementation(libs.kotest.runner.junit5)
      }
    }
  }
}

val generateFormatRegistry =
  tasks.register<GenerateFormatRegistry>("generateFormatRegistry") {
    specFile.set(layout.projectDirectory.file("formats/formats.json"))
    outputFile.set(
      layout.projectDirectory.file(
        "src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/formats/GeneratedFormatRegistry.kt",
      ),
    )
  }

// NOTE: the verify tasks intentionally reference the same paths directly instead of
// depending on generateFormatRegistry. Depending on the generator would regenerate
// the committed file before verification and the drift check would never fail.
tasks.register<VerifyGeneratedSources>("verifyGeneratedSources") {
  formatSpecFile.set(layout.projectDirectory.file("formats/formats.json"))
  generatedRegistryFile.set(
    layout.projectDirectory.file(
      "src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/formats/GeneratedFormatRegistry.kt",
    ),
  )
}

tasks.register<VerifyFormatRegistry>("verifyFormatRegistry") {
  specFile.set(layout.projectDirectory.file("formats/formats.json"))
  generatedRegistry.set(
    layout.projectDirectory.file(
      "src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/formats/GeneratedFormatRegistry.kt",
    ),
  )
  formatValidatorsDir.set(
    layout.projectDirectory.dir("src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/formats"),
  )
  draftConfigsDir.set(
    layout.projectDirectory.dir("src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/config"),
  )
  apiDumpFile.set(layout.projectDirectory.file("api/json-schema-validator.api"))
}