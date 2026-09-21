package io.github.optimumcode.json.schema.gates

private const val CHECK_NAME: String = "spec-fixture-parity"

/**
 * The result of the parity check. [violations] fails the gate; the other fields are
 * reported separately so declared target-specific skips are visible without failing.
 */
public data class ParityOutcome(
  val violations: List<GateViolation>,
  val targetSkips: Map<String, List<String>>,
  val targetsWithoutReport: List<String>,
) {
  public val isSuccess: Boolean
    get() = violations.isEmpty()
}

/**
 * Compares the collection reports produced by every available target.
 *
 * The check fails when:
 * - a required target produced no report at all;
 * - a report was produced against a different manifest revision (stale report);
 * - a fixture from the manifest was collected zero times on any target
 *   (including the case where *every* target collected zero - agreeing on
 *   zero is still a failure);
 * - a fixture was collected a different number of times across targets;
 * - a report contains a fixture that is not in the manifest.
 */
public object FixtureParityChecker {
  public fun check(
    manifest: List<FixtureExpectation>,
    manifestRevision: String,
    reports: List<CollectionReport>,
    requiredTargets: Set<String>,
    knownTargets: Set<String>,
  ): ParityOutcome {
    val violations = mutableListOf<GateViolation>()

    val reportsByTarget = LinkedHashMap<String, CollectionReport>()
    for (report in reports) {
      val previous = reportsByTarget.put(report.target, report)
      if (previous != null) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "target '${report.target}' produced more than one collection report",
          )
      }
    }

    for (required in requiredTargets.sorted()) {
      if (required !in reportsByTarget) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "target '$required' is required on this host but produced no collection report",
          )
      }
    }

    for ((target, report) in reportsByTarget) {
      if (report.manifestRevision != manifestRevision) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "report from target '$target' was produced against manifest revision " +
              "'${report.manifestRevision}' but the current manifest revision is '$manifestRevision'; " +
              "the report is stale, re-run the target tests",
          )
      }
    }

    val manifestById = manifest.associateBy(FixtureExpectation::id)
    for (expectation in manifest) {
      val countsByTarget =
        linkedMapOf<String, Int>().apply {
          for ((target, report) in reportsByTarget) {
            put(target, report.fixtures.firstOrNull { it.id == expectation.id }?.collected ?: 0)
          }
        }
      val zeroTargets = countsByTarget.filterValues { it == 0 }.keys
      if (zeroTargets.isNotEmpty()) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "fixture '${expectation.id}' was collected zero times on " +
              zeroTargets.joinToString(", ") { "'$it'" } +
              " (expected ${expectation.tests} tests, counts per target: ${formatCounts(countsByTarget)})",
          )
        continue
      }
      val distinctCounts = countsByTarget.values.toSet()
      if (distinctCounts.size > 1) {
        violations +=
          GateViolation(
            CHECK_NAME,
            "fixture '${expectation.id}' was collected a different number of times per target: " +
              formatCounts(countsByTarget),
          )
      }
      val expected = expectation.tests
      for ((target, count) in countsByTarget) {
        if (count != expected) {
          violations +=
            GateViolation(
              CHECK_NAME,
              "fixture '${expectation.id}' was collected $count times on '$target' " +
                "but the manifest expects $expected",
            )
        }
      }
    }

    for ((target, report) in reportsByTarget) {
      for (fixture in report.fixtures) {
        if (fixture.id !in manifestById) {
          violations +=
            GateViolation(
              CHECK_NAME,
              "report from target '$target' contains fixture '${fixture.id}' " +
                "which is not present in the manifest; regenerate the manifest",
            )
        }
      }
    }

    val targetSkips =
      reportsByTarget
        .mapValues { (_, report) ->
          report.fixtures
            .filter { it.skipped.isNotEmpty() }
            .flatMap { fixture -> fixture.skipped.map { test -> "${fixture.id}: $test" } }
        }.filterValues { it.isNotEmpty() }

    return ParityOutcome(
      violations = violations,
      targetSkips = targetSkips,
      targetsWithoutReport = knownTargets.sorted() - reportsByTarget.keys,
    )
  }

  private fun formatCounts(countsByTarget: Map<String, Int>): String =
    countsByTarget.entries.joinToString(", ") { (target, count) -> "$target=$count" }
}
