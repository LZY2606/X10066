import tasks.CheckCleanWorkingTree
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.Task

plugins {
  alias(libs.plugins.kotlin.binaryCompatibility)
}

allprojects {
  repositories {
    mavenCentral()
  }
}

apiValidation {
  ignoredProjects += listOf("benchmark", "test-suites", "gate-fixtures", "json-schema-validator-bom")
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
 * Aggregates the module test tasks so `./gradlew test` from the root executes
 * every module, matching the CI entry point.
 */
val aggregateTest: TaskProvider<Task> =
  tasks.register("test") {
    group = "verification"
    description = "Runs tests of every module"
  }

// Select exactly the test tasks that execute on this host.
// JVM and the two Node.js backends run on every host; native tasks follow the OS.
// iOS simulator tasks need a full Xcode installation and are opt-in (-Pgate.ios=true).
val hostOsName = System.getProperty("os.name").lowercase()
val includeIos = providers.gradleProperty("gate.ios").orNull == "true"
val portableTestTasks = listOf("jvmTest", "jsNodeTest", "wasmJsNodeTest")
val hostNativeTestTasks: List<String> =
  when {
    hostOsName.startsWith("mac") ->
      buildList {
        add("macosArm64Test")
        if (includeIos) {
          add("iosX64Test")
          add("iosArm64Test")
          add("iosSimulatorArm64Test")
        }
      }
    hostOsName.startsWith("windows") -> listOf("mingwX64Test")
    else -> listOf("linuxX64Test", "linuxArm64Test")
  }

subprojects.forEach { subproject ->
  subproject.afterEvaluate {
    (portableTestTasks + hostNativeTestTasks).forEach { taskName ->
      tasks.findByName(taskName)?.let { task ->
        aggregateTest.configure { dependsOn(task) }
      }
    }
  }
}

/**
 * Unified, offline-reproducible multiplatform gate.
 *
 * It runs the same entry on developer machines and CI:
 * `./gradlew verify --offline` (dependencies resolved once via the wrapper).
 *
 * The gate enforces:
 * - generated format registry does not drift from the validator sources
 *   ([verifyFormatsRegistry]);
 * - public API surface matches the committed *.api files (`apiCheck`);
 * - every common gate fixture is collected, with identical counts, on every
 *   target that can execute on this host, while target-specific fixtures are
 *   reported separately (`:gate-fixtures:verifyGateFixtures`);
 * - the working tree is clean after generation, i.e. no stale generated output;
 * - the complete test suites still pass (`test`).
 *
 * Sub-command failures propagate: a failing test task leaves no report and the
 * aggregate verification fails; zero fixture collection and stale generated
 * artifacts are both hard errors.
 */
val checkCleanWorkingTree =
  tasks.register<CheckCleanWorkingTree>("checkCleanWorkingTree") {
    generatedPaths.set(
      listOf(
        // generated format registry lives among the validator sources
        "json-schema-validator/src/commonMain/kotlin/io/github/optimumcode/json/schema/internal/formats",
      ),
    )
    // The spec test suite is delivered as a submodule; a not-yet-initialized
    // checkout is an environment state, not generated drift.
    ignoredPaths.set(listOf("test-suites/schema-test-suite"))
  }

tasks.register("verify") {
  group = "verification"
  description = "Unified multiplatform gate: generation drift, fixture parity, API alignment and tests"
  dependsOn(":json-schema-validator:apiCheck", ":json-schema-validator-objects:apiCheck")
  dependsOn(":json-schema-validator:verifyFormatsRegistry")
  dependsOn(":gate-fixtures:verifyGateFixtures")
  dependsOn(aggregateTest)
  finalizedBy(checkCleanWorkingTree)
}
