package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Generates remote schemas file for JSON schema test-suite
 */
abstract class GenerateRemoteSchemas : DefaultTask() {
  // Marked @Internal because the schema-test-suite submodule may be absent in offline
  // environments. The task degrades to an empty remotes file in that case.
  @Internal
  val remotes: Provider<Directory> =
    project.objects.directoryProperty()
      .convention(
        project.layout.projectDirectory.dir("schema-test-suite/remotes"),
      )

  @Internal
  val script: Provider<RegularFile> =
    project.objects.fileProperty()
      .convention(
        project.layout.projectDirectory.file("schema-test-suite/bin/jsonschema_suite"),
      )

  @OutputFile
  val remotesFile: Provider<RegularFile> =
    project.objects.fileProperty()
      .convention(
        project.layout.buildDirectory.file("remotes.json"),
      )

  @get:Inject
  protected abstract val execService: ExecOperations

  init {
    group = "generation"
    description = "Generates remote schema files for test suites"
  }

  @TaskAction
  protected fun generate() {
    val scriptFile = script.get().asFile
    val remotesDir = remotes.get().asFile
    val output = remotesFile.get().asFile
    if (!scriptFile.isFile || !remotesDir.isDirectory) {
      logger.warn(
        "JSON-Schema-Test-Suite submodule is not checked out " +
          "(missing ${if (scriptFile.isFile) remotesDir else scriptFile}). " +
          "Writing an empty remotes file; upstream test suites will be skipped.",
      )
      output.writeText("{}")
      return
    }
    output.outputStream().use { out ->
      execService.exec {
        standardOutput = out
        executable = "python3"
        args(
          script.get().asFile.path,
          "remotes",
        )
      }
    }
  }
}