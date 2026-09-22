import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.gradle.api.tasks.testing.Test as JvmTest
import tasks.GenerateGateFixtures
import tasks.VerifyGateReports

plugins {
  convention.kotlin
  convention.`multiplatform-jvm`
  convention.`multiplatform-tests`
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  jvm()

  js {
    nodejs()
  }

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    nodejs()
  }

  macosArm64()
  iosX64()
  iosArm64()
  iosSimulatorArm64()
  linuxX64()
  linuxArm64()
  mingwX64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonTest {
      dependencies {
        implementation(projects.jsonSchemaValidator)
        implementation(libs.kotest.assertions.core)
        implementation(libs.kotest.framework.engine)
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
        implementation(libs.kotlin.serialization.json)
      }
    }
    jvmTest {
      dependencies {
        implementation(libs.kotest.runner.junit5)
      }
    }
  }
}

val generateGateFixtures =
  tasks.register<GenerateGateFixtures>("generateGateFixtures") {
    commonFixturesDir.set(layout.projectDirectory.dir("src/commonFixtures"))
    targetFixturesDir.set(layout.projectDirectory.dir("src/targetFixtures"))
    outputDir.set(layout.buildDirectory.dir("generated/gateFixtures"))
  }

val generatedSrcDir = layout.buildDirectory.dir("generated/gateFixtures")

kotlin {
  sourceSets {
    commonTest {
      kotlin.srcDir(generatedSrcDir.map { it.dir("common") })
    }
    named("jvmTest") {
      kotlin.srcDir(generatedSrcDir.map { it.dir("jvm") })
    }
    named("jsTest") {
      kotlin.srcDir(generatedSrcDir.map { it.dir("js") })
    }
    named("wasmJsTest") {
      kotlin.srcDir(generatedSrcDir.map { it.dir("wasmJs") })
    }
    named("nativeTest") {
      kotlin.srcDir(generatedSrcDir.map { it.dir("native") })
    }
  }
}

// The generated sources are required by KSP/compile on every target.
tasks.matching {
  it.name.startsWith("compileTest") || it.name.startsWith("kspTest")
}.configureEach {
  dependsOn(generateGateFixtures)
}

val gateReportFile =
  { taskName: String ->
    layout.buildDirectory
      .file("gate-reports/$taskName.json")
      .get()
      .asFile
  }

tasks.withType<JvmTest>().configureEach {
  dependsOn(generateGateFixtures)
  doFirst {
    val report = gateReportFile(name)
    report.parentFile.mkdirs()
    report.delete()
    environment("GATE_FIXTURE_REPORT", report.absolutePath)
  }
}

tasks.withType<KotlinJsTest>().configureEach {
  dependsOn(generateGateFixtures)
  doFirst {
    val report = gateReportFile(name)
    report.parentFile.mkdirs()
    report.delete()
    environment("GATE_FIXTURE_REPORT", report.absolutePath)
  }
}

tasks.withType<KotlinNativeTest>().configureEach {
  dependsOn(generateGateFixtures)
  doFirst {
    val report = gateReportFile(name)
    report.parentFile.mkdirs()
    report.delete()
    environment("GATE_FIXTURE_REPORT", report.absolutePath)
  }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
  dependsOn(generateGateFixtures)
  doFirst {
    val report = gateReportFile(name)
    report.parentFile.mkdirs()
    report.delete()
    environment("SIMCTL_CHILD_GATE_FIXTURE_REPORT", report.absolutePath)
  }
}

/**
 * Test tasks that can execute on the current host, grouped by OS family.
 * iOS simulator tasks are opt-in (property gate.ios=true) because they need a
 * booted simulator; the deterministic host set already covers native code.
 */
val includeIos = providers.gradleProperty("gate.ios").orNull == "true"
val hostOs = System.getProperty("os.name").lowercase()
val hostTestTasks: List<String> =
  when {
    hostOs.startsWith("mac") ->
      buildList {
        add("jvmTest")
        add("jsNodeTest")
        add("wasmJsNodeTest")
        add("macosArm64Test")
        if (includeIos) {
          add("iosX64Test")
          add("iosArm64Test")
          add("iosSimulatorArm64Test")
        }
      }
    hostOs.startsWith("windows") ->
      listOf("jvmTest", "jsNodeTest", "wasmJsNodeTest", "mingwX64Test")
    else ->
      listOf("jvmTest", "jsNodeTest", "wasmJsNodeTest", "linuxX64Test", "linuxArm64Test")
  }

val runGateTests by tasks.registering {
  group = "verification"
  description = "Runs gate fixture tests on every target executable on this host"
  hostTestTasks.forEach { taskName ->
    val task = tasks.findByName(taskName)
    if (task != null) {
      dependsOn(task)
    }
  }
}

val verifyGateFixtures =
  tasks.register<VerifyGateReports>("verifyGateFixtures") {
    dependsOn(runGateTests)
    summaryFile.set(layout.buildDirectory.file("reports/gate/gate-summary.txt"))
  }

project.afterEvaluate {
  // Only targets whose test tasks are enabled on this host are expected to
  // produce reports (e.g. linuxArm64 on an x64 runner without emulation).
  val availableTargets =
    hostTestTasks.filter { taskName ->
      tasks.findByName(taskName)?.enabled ?: false
    }
  verifyGateFixtures.configure {
    expectedTargets.set(availableTargets)
    reportFiles.from(
      availableTargets.map { taskName ->
        layout.buildDirectory.file("gate-reports/$taskName.json")
      },
    )
  }
}
