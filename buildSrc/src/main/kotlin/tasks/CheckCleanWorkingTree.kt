package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.gradle.process.internal.ExecException

/**
 * Fails the build if a committed generated artifact drifts after generation.
 *
 * Only files inside [generatedPaths] are inspected: a tracked modification or
 * deletion, or an uncommitted new file under one of those output locations,
 * means the committed generation result drifted. Every other working-tree
 * change (hand-written sources, build scripts in a feature branch) is ignored.
 */
abstract class CheckCleanWorkingTree : DefaultTask() {
  /**
   * Repository-relative directories that contain generated output.
   * Untracked files under these paths are treated as uncommitted generation.
   */
  @get:Input
  abstract val generatedPaths: ListProperty<String>

  /** Paths that are environment state rather than generated drift. */
  @get:Input
  abstract val ignoredPaths: ListProperty<String>

  @get:Inject
  abstract val execOperations: ExecOperations

  init {
    group = "verification"
    description = "Fails if generation changed tracked files or emitted uncommitted generated files"
  }

  @TaskAction
  protected fun check() {
    val porcelain =
      try {
        val stdout = java.io.ByteArrayOutputStream()
        execOperations.exec {
          // Compare the working tree against the index: staged additions are part
          // of the prepared deliverable and must not count as drift. Only files
          // that differ from what is staged indicate a generator re-ran.
          commandLine("git", "diff", "--name-only", "--no-ext-diff")
          standardOutput = stdout
        }
        execOperations.exec {
          commandLine("git", "ls-files", "--others", "--exclude-standard")
          standardOutput = stdout
        }
        stdout.toString(Charsets.UTF_8)
      } catch (ex: ExecException) {
        throw GradleException(
          "cannot run 'git status --porcelain' to verify generated artifacts: ${ex.message}",
          ex,
        )
      }

    val generated = generatedPaths.get()
    val ignored = ignoredPaths.get()
    val violations = mutableListOf<String>()

    porcelain.lineSequence().forEach { raw ->
      val path = raw.trimEnd().trim('"')
      if (path.isEmpty()) {
        return@forEach
      }
      if (ignored.any { prefix -> path == prefix || path.startsWith("$prefix/") }) {
        return@forEach
      }
      val underGeneratedPath =
        generated.any { prefix -> path == prefix || path.startsWith("$prefix/") }
      if (underGeneratedPath) {
        violations += path
      }
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine(
            "generated artifacts drifted: regenerate with the generation tasks " +
              "and commit the result:",
          )
          violations.forEach { appendLine("  $it") }
        },
      )
    }
    logger.lifecycle("Generated artifacts are up-to-date")
  }
}
