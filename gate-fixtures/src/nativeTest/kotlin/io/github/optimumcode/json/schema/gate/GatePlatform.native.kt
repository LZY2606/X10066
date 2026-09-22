package io.github.optimumcode.json.schema.gate

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual val currentTargetFamily: TargetFamily = TargetFamily.NATIVE

@OptIn(ExperimentalForeignApi::class)
internal actual fun readGateEnv(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeGateReport(
  path: String,
  content: String,
) {
  val file =
    fopen(path, "w") ?: error("cannot open gate report file for writing: $path")
  try {
    if (fputs(content, file) < 0) {
      error("failed writing gate report to $path")
    }
  } finally {
    fclose(file)
  }
}
