package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Verifies that checked-in generated sources do not drift.
 *
 * The task depends on the generators (API dump tasks) so generation runs first;
 * afterwards the working tree must be clean for every generated path.
 * A missing generated file or a dirty generated path fails the build with
 * the exact paths that drifted.
 */
abstract class VerifyGeneratedSources : DefaultTask() {
  /**
   * Repository-relative paths that contain generated sources.
   */
  @get:Input
  abstract val generatedPaths: ListProperty<String>

  @get:Internal
  abstract val repositoryDir: DirectoryProperty

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  @get:Inject
  protected abstract val execOperations: ExecOperations

  init {
    group = "verification"
    description = "Checks generated sources are present and the working tree stays clean after generation"
    // the working tree state is not trackable as a task input:
    // a drift gate that is skipped as up-to-date would silently pass
    outputs.upToDateWhen { false }
  }

  @TaskAction
  fun verify() {
    val problems = mutableListOf<String>()
    val repoDir = repositoryDir.get().asFile
    val paths = generatedPaths.get()
    if (paths.isEmpty()) {
      problems += "no generated paths configured: the drift gate cannot verify anything and must not pass silently"
    }

    for (path in paths) {
      val dir = repoDir.resolve(path)
      when {
        !dir.exists() ->
          problems += "generated path '$path' does not exist: run the generation tasks and commit the result"

        dir.isDirectory && dir.walkTopDown().none { it.isFile } ->
          problems += "generated path '$path' is empty: run the generation tasks and commit the result"

        dir.isFile && dir.length() == 0L ->
          problems += "generated file '$path' is empty: run the generation tasks and commit the result"
      }
    }

    if (problems.isEmpty()) {
      val stdout = ByteArrayOutputStream()
      val stderr = ByteArrayOutputStream()
      val result =
        execOperations.exec {
          commandLine(
            listOf("git", "-C", repoDir.absolutePath, "status", "--porcelain", "--") + paths,
          )
          standardOutput = stdout
          errorOutput = stderr
          isIgnoreExitValue = true
        }
      if (result.exitValue != 0) {
        problems +=
          "cannot inspect the working tree with git (exit code ${result.exitValue}): " +
            stderr.toString(Charsets.UTF_8).trim()
      } else {
        val dirty = stdout.toString(Charsets.UTF_8).trim()
        if (dirty.isNotEmpty()) {
          problems +=
            "generated sources drifted from the committed state; " +
              "regenerate them and commit the result. Drifted entries:\n" +
              dirty.lineSequence().joinToString("\n") { "    $it" }
        }
      }
    }

    writeReport(paths, problems)
    if (problems.isNotEmpty()) {
      throw GradleException(
        problems.joinToString(
          prefix = "generated sources verification failed with ${problems.size} problem(s):\n - ",
          separator = "\n - ",
        ),
      )
    }
    logger.lifecycle("generated sources OK: {} generated path(s) are present and clean", paths.size)
  }

  private fun writeReport(
    paths: List<String>,
    problems: List<String>,
  ) {
    val out = reportFile.get().asFile
    out.parentFile.mkdirs()
    out.printWriter().use { writer ->
      writer.println("generated sources verification report")
      writer.println("generated paths:")
      paths.forEach { writer.println("  $it") }
      if (problems.isEmpty()) {
        writer.println("result: OK (working tree clean for all generated paths)")
      } else {
        writer.println("result: FAILED")
        problems.forEach { writer.println("  - $it") }
      }
    }
  }
}
