package io.github.optimumcode.json.schema.gate

import java.nio.file.Files
import java.nio.file.Paths

internal actual val currentTargetFamily: TargetFamily = TargetFamily.JVM

internal actual fun readGateEnv(name: String): String? = System.getenv(name)

internal actual fun writeGateReport(
  path: String,
  content: String,
) {
  Files.writeString(Paths.get(path), content)
}
