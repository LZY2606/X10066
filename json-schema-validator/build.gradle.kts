@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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

val formatsDirectory =
  layout.projectDirectory.dir(
    "src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/formats",
  )
val generatedRegistryFile = formatsDirectory.file("GeneratedFormatsRegistry.kt")

val generateFormatsRegistry =
  tasks.register<tasks.GenerateFormatsRegistry>("generateFormatsRegistry") {
    formatsSourceDir.set(formatsDirectory)
    registryFile.set(generatedRegistryFile)
    verify.set(false)
  }

val verifyFormatsRegistry =
  tasks.register<tasks.GenerateFormatsRegistry>("verifyFormatsRegistry") {
    formatsSourceDir.set(formatsDirectory)
    // The verify task only reads the committed file; it must not be an output owner.
    expectedRegistryFile.set(generatedRegistryFile)
    registryFile.set(
      layout.buildDirectory.file("tmp/verifyFormatsRegistry/GeneratedFormatsRegistry.kt"),
    )
    verify.set(true)
  }

tasks.named("check") {
  dependsOn(verifyFormatsRegistry)
}