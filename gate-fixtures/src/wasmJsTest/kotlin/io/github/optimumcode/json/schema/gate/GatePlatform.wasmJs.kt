@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.optimumcode.json.schema.gate

import kotlin.js.JsString
import kotlin.js.toJsString


internal actual val currentTargetFamily: TargetFamily = TargetFamily.WASM_JS

@JsFun(
  """(name) => {
    const value = process.env[name];
    return value === undefined ? null : value;
  }""",
)
private external fun processEnv(name: JsString): JsString?

@JsFun("""(path) => require('fs').existsSync(path)""")
private external fun fsExists(path: JsString): Boolean

@JsFun("""(path) => require('fs').mkdirSync(path, { recursive: true })""")
private external fun fsMkdir(path: JsString)

@JsFun("""(path, content) => require('fs').writeFileSync(path, content)""")
private external fun fsWriteFile(
  path: JsString,
  content: JsString,
)

internal actual fun readGateEnv(name: String): String? =
  processEnv(name.toJsString())?.toString()

internal actual fun writeGateReport(
  path: String,
  content: String,
) {
  val parent = path.substringBeforeLast('/', ".")
  if (!fsExists(parent.toJsString())) {
    fsMkdir(parent.toJsString())
  }
  fsWriteFile(path.toJsString(), content.toJsString())
}
