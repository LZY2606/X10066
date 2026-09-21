package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a committed generated source no longer matches its generator input.
 * This is the "generated sources must not drift" gate: regeneration must leave the working
 * tree clean.
 */
abstract class VerifyGeneratedSources : DefaultTask() {
  // @Internal: these paths overlap the output file of generateFormatRegistry. Declaring
  // them as inputs would force regeneration before verification whenever both tasks
  // run in one invocation, making drift undetectable. The actual file contents are
  // declared as inputs via [specContent] and read directly in the action.
  @get:Internal
  abstract val formatSpecFile: RegularFileProperty

  @get:Internal
  abstract val generatedRegistryFile: RegularFileProperty

  @get:Input
  val specContent: String
    get() = formatSpecFile.get().asFile.readText()

  init {
    group = "verification"
    description = "Verifies committed generated sources are up to date with their inputs"
  }

  @TaskAction
  fun verify() {
    val problems = mutableListOf<String>()
    verifyGenerated(
      description = "format registry",
      regenerate = { FormatRegistryGenerator.generate(FormatRegistryGenerator.parse(formatSpecFile.get().asFile)) },
      generatedFile = generatedRegistryFile.get().asFile,
      updateHint = "./gradlew :json-schema-validator:generateFormatRegistry",
      problems = problems,
    )
    if (problems.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("generated sources are stale:")
          problems.forEach { appendLine("  - $it") }
        },
      )
    }
  }

  private fun verifyGenerated(
    description: String,
    regenerate: () -> String,
    generatedFile: java.io.File,
    updateHint: String,
    problems: MutableList<String>,
  ) {
    val relativePath: String = generatedFile.relativeToOrSelf(project.rootDir).path
    if (!generatedFile.isFile) {
      problems += "$description: generated file $relativePath is missing (run '$updateHint')"
      return
    }
    val expected: String = regenerate()
    val actual: String = generatedFile.readText()
    if (actual == expected) {
      return
    }
    val expectedLines = expected.lines()
    val actualLines = actual.lines()
    val firstDiff: Int = expectedLines.zip(actualLines).indexOfFirst { (e, a) -> e != a }
    val diffContext =
      if (firstDiff >= 0) {
        "first difference at line ${firstDiff + 1}: expected '${expectedLines[firstDiff]}' but found '${actualLines[firstDiff]}'"
      } else {
        "line count differs (expected ${expectedLines.size}, found ${actualLines.size})"
      }
    problems += "$description: $relativePath is stale ($diffContext). Run '$updateHint' and commit the result"
  }
}