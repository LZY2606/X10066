package io.github.optimumcode.json.schema.gates

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

private const val SUITES_DIR: String = "suites"
private const val TARGET_SPECIFIC_DIR: String = "target-specific"
private const val FIXTURE_EXTENSION: String = ".json"

/**
 * A fixture file collected from the fixtures directory.
 *
 * @property id fixture identifier: path segments under `suites/` joined with `/`,
 *   without the `.json` extension (e.g. `common/type-and-enum`). Segments are used
 *   instead of the platform path separator so the id is identical on every target.
 * @property category the directory under `suites/` that holds the fixture
 *   (e.g. `common` or `optional/format`)
 * @property groups parsed suite groups
 */
public data class CollectedFixture(
  val id: String,
  val category: String,
  val groups: List<SuiteGroup>,
) {
  public val testCount: Int
    get() = groups.sumOf { it.tests.size }
}

/**
 * Collects the spec fixtures from a directory with the layout described in
 * `spec-fixtures/README.md`. The collector is shared by every target so the
 * parity gate compares exactly the same collection logic everywhere.
 */
public class FixtureCollector(
  private val fs: FileSystem,
  private val json: Json = Json,
) {
  /**
   * Collects and parses every fixture under `<fixturesDir>/suites`.
   * Files are returned sorted by id so the result is deterministic on every target.
   */
  public fun collect(fixturesDir: Path): List<CollectedFixture> {
    val suitesDir = fixturesDir / SUITES_DIR
    require(fs.exists(suitesDir)) { "fixtures suites directory $suitesDir does not exist" }
    return collectJsonFiles(suitesDir)
      .map { fixtureFile ->
        val segments = fixtureFile.relativeTo(suitesDir).segments
        val id = segments.joinToString("/").removeSuffix(FIXTURE_EXTENSION)
        val groups: List<SuiteGroup> =
          fs.read(fixtureFile) {
            json.decodeFromString<List<SuiteGroup>>(readUtf8())
          }
        CollectedFixture(
          id = id,
          category = segments.dropLast(1).joinToString("/"),
          groups = groups,
        )
      }.sortedBy(CollectedFixture::id)
  }

  private fun collectJsonFiles(dir: Path): List<Path> =
    fs.list(dir)
      .sortedBy(Path::name)
      .flatMap { path ->
        when {
          fs.metadata(path).isDirectory -> collectJsonFiles(path)
          path.name.endsWith(FIXTURE_EXTENSION) -> listOf(path)
          else -> emptyList()
        }
      }
}

/**
 * Loads the declared skips for [target] from `spec-fixtures/target-specific/<target>.json`.
 * Returns `null` when the target declares no skips.
 */
public fun loadTargetSkips(
  fs: FileSystem,
  fixturesDir: Path,
  target: String,
  json: Json = Json,
): TargetSkips? {
  val skipsFile = fixturesDir / TARGET_SPECIFIC_DIR / "$target$FIXTURE_EXTENSION"
  if (!fs.exists(skipsFile)) {
    return null
  }
  return fs.read(skipsFile) {
    json.decodeFromString<TargetSkips>(readUtf8())
  }
}
