package io.github.optimumcode.json.schema.gate

internal actual val currentTargetFamily: TargetFamily = TargetFamily.JS

internal actual fun readGateEnv(name: String): String? = readProcessEnv(name)

internal actual fun writeGateReport(
  path: String,
  content: String,
) {
  val parent = path.substringBeforeLast('/', ".")
  if (!nodeFsExists(parent)) {
    nodeMkdir(parent)
  }
  nodeWriteFile(path, content)
}

private fun readProcessEnv(name: String): String? {
  val value = js("process.env[name]")
  return value?.unsafeCast<String>()
}

private fun nodeFsExists(path: String): Boolean = js("require('fs').existsSync(path)")

private fun nodeMkdir(path: String) {
  js("require('fs').mkdirSync(path, { recursive: true })")
}

private fun nodeWriteFile(
  path: String,
  content: String,
) {
  js("require('fs').writeFileSync(path, content)")
}
