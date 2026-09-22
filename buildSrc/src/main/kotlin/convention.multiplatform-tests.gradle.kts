import org.jetbrains.kotlin.gradle.plugin.KotlinTargetWithTests
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension
import tasks.KarmaReportWorkaround

plugins {
  id("convention.kotlin")
  id("com.google.devtools.ksp")
  id("io.kotest")
}

val macOsAllTest by tasks.register("macOsAllTest") {
  group = "verification"
  description = "runs all tests for MacOS and IOS targets"
}

val windowsAllTest by tasks.register("windowsAllTest") {
  group = "verification"
  description = "runs all tests for Windows targets"
}
val linuxAllTest by tasks.register("linuxAllTest") {
  group = "verification"
  description = "runs all tests for Linux targets"
}

// iOS simulator tests need a full Xcode installation. They join the macOs group
// only when the gate.ios property is set; the deterministic host set (macos*,
// jvm, js, wasmJs) runs without it so the gate is reproducible on CI runners
// that only have the command line tools.
val includeIosSimulatorTests =
  providers.gradleProperty("gate.ios").orNull == "true"

kotlin.targets.configureEach {
  if (this !is KotlinTargetWithTests<*, *>) {
    return@configureEach
  }
  val testTask = tasks.named("${name}Test")
  when {
    name.startsWith("ios") -> {
      if (includeIosSimulatorTests) {
        macOsAllTest.dependsOn(testTask)
      }
    }

    name.startsWith("macos") -> {
      macOsAllTest.dependsOn(testTask)
    }

    name.startsWith("mingw") -> {
      windowsAllTest.dependsOn(testTask)
    }

    else -> {
      linuxAllTest.dependsOn(testTask)
    }
  }
}

val karmaReportWorkaround =
  tasks.register<KarmaReportWorkaround>("karmaReportWorkaround") {
    val nodeJsRootExtension = rootProject.extensions.getByType<NodeJsRootExtension>()
    val wasmNodeJsRootExtension = rootProject.extensions.getByType<WasmNodeJsRootExtension>()
    shouldRunAfter(nodeJsRootExtension.npmInstallTaskProvider, wasmNodeJsRootExtension.npmInstallTaskProvider)
  }

tasks.withType<KotlinJsTest> {
  when (name) {
    "jsBrowserTest" -> dependsOn(karmaReportWorkaround)

    // For some reasons, the output of wasmJsBrowserTest is not captured correclty
    // NOTE: the reason is the same as for JS but same workaround does not work...
    "wasmJsBrowserTest" -> failOnNoDiscoveredTests = false

    else -> Unit
  }
}