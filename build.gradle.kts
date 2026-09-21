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

/**
 * Single offline entry point for the multiplatform gates.
 * Fails if generated sources drift, if the format registry diverges from the
 * exported API or draft defaults, or if common fixtures are not collected
 * consistently on every available target. Target-specific skips are reported
 * separately by :test-suites:verifyFixtureParity.
 */
tasks.register("verify") {
  group = "verification"
  description = "Runs all offline verification gates (generated sources, format registry, fixture parity)"
  dependsOn(
    ":json-schema-validator:verifyGeneratedSources",
    ":json-schema-validator:verifyFormatRegistry",
    ":test-suites:verifyFixtureParity",
  )
}

val hostName: String? = System.getProperty("os.name")

/**
 * Root-level test entry point. Aggregates the per-host test groups
 * (`linuxAllTest`, `macOsAllTest`, `windowsAllTest`) registered by the
 * `convention.multiplatform-tests` convention across all modules. Test tasks for
 * targets that cannot execute on the current host are skipped. This mirrors the
 * host-split tasks used by the CI workflows.
 *
 * Simulator/device targets (e.g. ios*) are not aggregated here: they require a full
 * Xcode installation and can never run on Linux/Windows hosts. On macOS CI they are
 * still covered by the existing `macOsAllTest` task; the aggregate only runs the
 * host-native target (`macosArm64`/`linuxX64`/`mingwX64`) plus jvm/js/wasmJs.
 */
tasks.register("test") {
  group = "verification"
  description = "Runs all multiplatform tests that can execute on this host"
  val hostNativeTestPrefix =
    when {
      hostName?.startsWith("Mac", ignoreCase = true) == true -> "macos"
      hostName?.startsWith("Windows", ignoreCase = true) == true -> "mingw"
      else -> "linux"
    }
  subprojects.forEach { subproject ->
    subproject.plugins.withId("convention.multiplatform-tests") {
      // Host-independent targets run on every host.
      dependsOn(
        subproject.tasks.matching {
          it.name in setOf("jvmTest", "jsTest", "wasmJsTest")
        },
      )
      // Only the native target of the current host is aggregated. Other native
      // targets either need a different linker (cross-linking fails) or an
      // emulator/device SDK that is not installed on CI hosts.
      dependsOn(
        subproject.tasks.matching {
          it.name.startsWith(hostNativeTestPrefix) && it.name.endsWith("Test")
        },
      )
    }
  }
}