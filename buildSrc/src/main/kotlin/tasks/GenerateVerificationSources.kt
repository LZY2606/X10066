package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Regenerates the checked-in verification sources (fixture manifest and expected formats).
 * The output is written into the source tree and must be committed.
 * [VerifyGeneratedSourcesDrift] fails the build when the checked-in content drifts
 * from a fresh regeneration.
 */
abstract class GenerateVerificationSources : DefaultTask() {
  @get:InputDirectory
  abstract val fixturesDir: DirectoryProperty

  @get:InputFile
  abstract val formatRegistrySource: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDir: DirectoryProperty

  init {
    group = "generation"
    description = "Regenerates the checked-in verification sources (fixture manifest, expected formats)"
  }

  @TaskAction
  fun generate() {
    val packageDir = packageDir(outputDir.get().asFile)
    packageDir.mkdirs()
    for ((fileName, content) in generatedFiles()) {
      File(packageDir, fileName).writeText(content)
    }
  }

  internal fun generatedFiles(): Map<String, String> =
    VerificationSourceGenerator.generatedFiles(
      fixturesDir.get().asFile,
      formatRegistrySource.get().asFile,
    )

  internal companion object {
    fun packageDir(baseDir: File): File =
      File(baseDir, VerificationSourceGenerator.GENERATED_PACKAGE.replace('.', '/'))
  }
}
