package io.github.optimumcode.json.schema.gate

/**
 * Target family the current test is running on.
 * The value is used to select which target-specific fixtures must be collected
 * and to label the gate report produced by the test run.
 */
internal enum class TargetFamily {
  JVM,
  JS,
  WASM_JS,
  NATIVE,
}

internal expect val currentTargetFamily: TargetFamily

/**
 * Reads an environment variable on every target, including the iOS simulator
 * (the simulator entry receives variables with the `SIMCTL_CHILD_` prefix,
 * which the actual implementations strip before lookup).
 */
internal expect fun readGateEnv(name: String): String?

/**
 * Writes the gate report as a UTF-8 file.
 * Report delivery is target-specific: JVM uses java.nio, JS requires Node `fs`,
 * wasmJs uses the Wasm Node syscall bridge and native targets use POSIX.
 */
internal expect fun writeGateReport(
  path: String,
  content: String,
)
