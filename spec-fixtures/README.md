# Spec fixtures for the multiplatform gates

This directory is the small, self-contained fixture set used by the
`:json-schema-validator-gates` module. It exists so the multiplatform
fixture-collection, format-registry, and generated-source gates can run
offline, without the `test-suites/schema-test-suite` submodule.

## Layout

- `suites/common/*.json` — fixtures every target must collect and execute.
  Files follow the JSON-Schema-Test-Suite format (a list of groups with
  `description`, `schema`, and `tests` with `description`, `data`, `valid`).
- `suites/optional/format/*.json` — format-assertion fixtures. They are
  executed with `FormatBehavior.ANNOTATION_AND_ASSERTION`, mirroring how the
  upstream suite treats `optional/format` as a separate category.
- `target-specific/<target>.json` — declared per-target skips. Each file has
  a human-readable `reason` and a `skips` map from fixture id
  (path under `suites/` without `.json`) to test descriptions that the target
  does not execute. Skips are reported separately by the parity gate; they
  never change the collected-count comparison.
- `format-registry-expectations.json` — expected default format behavior per
  draft (`ASSERTION` or `VOCABULARY`) and the exported-API fragments that the
  public API dump must contain.

## Gates

All gates are wired into the root `verify` task and run offline:

```sh
./gradlew verify
```

- `verifySpecFixtureManifest` — regenerates the manifest from `suites/` and
  fails if the committed generated source
  (`json-schema-validator-gates/src/commonMain/kotlin/io/github/optimumcode/json/schema/gates/generated/SpecFixtureManifest.kt`)
  drifts. Regenerate with `./gradlew :json-schema-validator-gates:generateSpecFixtureManifest`;
  the working tree must be clean afterwards.
- `verifySpecFixtureParity` — compares per-target collection reports
  (`json-schema-validator-gates/build/reports/spec-fixtures/<target>.json`).
  Any common fixture collected zero times on a target, or collected a
  different number of times across targets, fails the build. Declared
  target-specific skips are reported separately in
  `build/reports/spec-fixtures/parity-report.txt`.
- `verifyFormatRegistry` — checks that every concrete format validator class
  is registered in `FormatAssertionFactory.KNOWN_FORMATS` (and vice versa),
  that the per-draft default format behavior matches
  `format-registry-expectations.json`, and that the exported API dump exposes
  the format API surface.

Each gate is a `JavaExec` subcommand of the gate CLI, so a failing subcommand
fails the whole `verify` run with the subcommand's diagnostics.

After changing anything under `suites/`, regenerate the manifest and rerun
the gates:

```sh
./gradlew :json-schema-validator-gates:generateSpecFixtureManifest verify
```
