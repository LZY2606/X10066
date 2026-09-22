import tasks.VerifyFixtureParity
import tasks.VerifyFormatRegistry
import tasks.VerifyGeneratedSources

plugins {
  alias(libs.plugins.kotlin.binaryCompatibility)
}

allprojects {
  repositories {
    mavenCentral()
  }
}

apiValidation {
  ignoredProjects += listOf("benchmark", "test-suites", "json-schema-validator-bom")
}

tasks.register("printKtlintVersion") {
  doLast {
    println(VersionConstants.KTLINT_VERSION)
  }
}

tasks.register("printDetektVersion") {
  doLast {
    println(libs.versions.detekt.get())
  }
}

val verifyFormatRegistry =
  tasks.register<VerifyFormatRegistry>("verifyFormatRegistry") {
    registryFile.set(
      layout.projectDirectory.file(
        "json-schema-validator/src/commonMain/kotlin/io/github/optimumcode/json/schema/" +
          "internal/factories/general/FormatAssertionFactory.kt",
      ),
    )
    formatsDir.set(
      layout.projectDirectory.dir(
        "json-schema-validator/src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/formats",
      ),
    )
    configsDir.set(
      layout.projectDirectory.dir(
        "json-schema-validator/src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/config",
      ),
    )
    apiDumpFile.set(layout.projectDirectory.file("json-schema-validator/api/json-schema-validator.api"))
    reportFile.set(layout.buildDirectory.file("reports/verify/format-registry.txt"))
  }

val verifyFixtureParity =
  tasks.register<VerifyFixtureParity>("verifyFixtureParity") {
    fixturesDir.set(layout.projectDirectory.dir("verification/fixtures"))
    reportFile.set(layout.buildDirectory.file("reports/verify/fixture-parity.txt"))
  }

project(":test-suites").plugins.withId("org.jetbrains.kotlin.multiplatform") {
  val testSuitesKotlin =
    project(":test-suites").extensions
      .getByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()
  verifyFixtureParity.configure {
    targets.set(provider { testSuitesKotlin.targets.names.toList() })
  }
}

val verifyGeneratedSources =
  tasks.register<VerifyGeneratedSources>("verifyGeneratedSources") {
    generatedPaths.set(
      listOf(
        "json-schema-validator/api",
        "json-schema-validator-objects/api",
      ),
    )
    repositoryDir.set(layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/verify/generated-sources.txt"))
    // generation runs first; the gate then rejects any drift from the committed state
    dependsOn(
      ":json-schema-validator:apiDump",
      ":json-schema-validator-objects:apiDump",
    )
  }

val verify =
  tasks.register("verify") {
    group = "verification"
    description =
      "Runs all repository gates: generated source drift, fixture collection parity " +
      "and format registry/API alignment"
    dependsOn(verifyGeneratedSources, verifyFixtureParity, verifyFormatRegistry)
  }

tasks.matching { it.name == "check" }.configureEach {
  dependsOn(verify)
}

// Host-runnable test tasks. Browser-based JS/Wasm runs are excluded because they
// require a local browser; cross-compiled native targets cannot execute on this host.
val hostTestTaskNames: List<String> =
  run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val names = mutableListOf("jvmTest", "jsNodeTest", "wasmJsNodeTest")
    when {
      "mac" in os || "darwin" in os ->
        names += if (arch == "aarch64" || arch == "arm64") "macosArm64Test" else "macosX64Test"

      "linux" in os ->
        names += if (arch == "aarch64" || arch == "arm64") "linuxArm64Test" else "linuxX64Test"

      "windows" in os -> names += "mingwX64Test"
    }
    names
  }

val hostTest =
  tasks.register("test") {
    group = "verification"
    description = "Runs every test task that can execute on this host"
  }

subprojects {
  plugins.withId("org.jetbrains.kotlin.multiplatform") {
    hostTest.configure {
      dependsOn(tasks.matching { it.name in hostTestTaskNames })
    }
  }
}
