# Changelog

## Unreleased

### Added

- **Root `verify` gate** (`./gradlew verify`): one offline, failure-propagating entry
  point that runs three independent checks:
  - `:json-schema-validator:verifyGeneratedSources` — committed generated sources must
    match their generator inputs; drift (including missing files) fails the build with
    the first differing line and the exact regeneration command.
  - `:json-schema-validator:verifyFormatRegistry` — the format registry is checked
    against (1) the actual `*FormatValidator` implementations in `internal/formats`,
    (2) the generated registry entries, (3) identical `null` (default) format behavior
    across all draft loader configs, and (4) the public API dump (`api/*.api`).
  - `:test-suites:verifyFixtureParity` — every available Kotlin target runs a
    `collect<Name>Fixtures` task; the gate fails when any target collects zero common
    or optional fixtures, or when any single fixture is missing from any target.
    Target-specific fixtures (`fixtures/target-specific/<jvm|js|native>/...`) are not
    required on every target; their skips are reported separately in
    `test-suites/build/reports/fixtureParity/target-specific-skips.txt` and in the
    Gradle log.
- **Generated format registry**: the hard-coded `KNOWN_FORMATS` map in
  `FormatAssertionFactory` is replaced by `GeneratedFormatRegistry`
  (`src/commonMain/.../internal/formats/GeneratedFormatRegistry.kt`), generated from a
  single declarative spec `json-schema-validator/formats/formats.json` by the
  `generateFormatRegistry` Gradle task. The generator lives in
  `buildSrc/.../tasks/FormatRegistryGenerator.kt` and is reused by the verify tasks, so
  generation and verification cannot drift from each other.
- **Committed gate fixtures** under `test-suites/fixtures/` in the same
  JSON-Schema-Test-Suite shape (suite array with `description`/`schema`/`tests`):
  - `common/` — must be collected on every target;
  - `optional/` — collected on every target but semantically optional per spec;
  - `target-specific/{jvm,js,native}/` — collected only by the matching target
    category.
- **`GateFixtureTest`** (common test, per-target `actual val gateTargetCategory`):
  executes all visible gate fixtures on each target and fails the test run if a target
  collects zero common/optional/target-specific cases. It can be located directly with
  `--tests "io.github.optimumcode.json.schema.suite.gate.GateFixtureTest"`.
- **`GeneratedFormatRegistryTest`**: one test per registered format asserting it is
  actually wired into schema loading under `ANNOTATION_AND_ASSERTION` (valid sample
  passes, invalid fails), plus registry-invariant tests (sorted unique kebab-case
  names, `formatNames`/`knownFormats` alignment, sample coverage of every format).
- CI (`build-and-test.yml`) style job now runs `verify` alongside
  `detekt detektAll ktlintCheck apiCheck`, so local and CI converge on the same
  offline-friendly gate.
- **Root `test` aggregate task**: the KMP project previously had no root `test`
  task (per-host groups `linuxAllTest`/`macOsAllTest`/`windowsAllTest` only). The new
  root `test` runs `jvmTest`/`jsTest`/`wasmJsTest` on every host plus only the native
  target of the current host (`macos*`/`mingw*`/`linux*`). Simulator/device targets
  (e.g. `ios*`) are intentionally not aggregated: they require a full Xcode install
  and cannot run on Linux/Windows; macOS CI keeps covering them via `macOsAllTest`.
  This avoids cross-linking failures (e.g. `-lunistring`) when a macOS machine
  depends on the Linux group.

### Changed

- `runTestSuites` in the upstream test-suite harness degrades to a loud skip (a
  `WARN:` log per draft) when the `schema-test-suite` git submodule is not checked out,
  instead of failing the test task. The committed gate fixtures still execute on every
  target, so this cannot mask a zero-collection regression. The submodule directory is
  treated as task input only for up-to-date checks via `@Internal`; when it is absent,
  `generateRemoteSchemas` writes an empty `{}` remotes file instead of running
  `python3` against a missing script.

### Implementation choices

- **Generated file is committed, not wired into the compile task graph.** The verify
  tasks read the committed file directly and intentionally do *not* depend on
  `generateFormatRegistry`; otherwise Gradle would regenerate before verifying and
  stale output could never be detected. `generateFormatRegistry` is run on demand;
  after running it the working tree must be clean.
- **Build-time gate vs runtime test are redundant on purpose.** The parity Gradle
  task checks fixture *collection* per target from the filesystem (fast, runs without a
  native/Js toolchain); `GateFixtureTest` checks that each target *executes* the
  collected cases through the real validator. A target that silently loses its fixtures
  fails at least one of them.
- **No special-casing.** Target grouping uses generic names (`jvm`, `js`, else
  `native`) and the runtime maps draft directory names to `SchemaType` entries by
  normalized names; nothing hard-codes a fixture file name.
- **No network, sleep, or absolute paths in the gate.** Collection and verification
  tasks use project-relative paths only; env vars exist solely for cross-target
  filesystem layout differences and are set by the Gradle build itself.
- **Subcommand failures propagate.** All three checks are separate task dependencies of
  `verify`; Gradle's non-zero exit and the aggregated `GradleException` messages carry
  per-item diagnostic context (target, fixture path, draft config file, first diff line,
  regeneration hint).

### Coverage gap this fills

- The format list lived in one hand-written map: adding a format validator without
  registering it (or vice versa), changing a draft's default format behavior, or
  breaking the public format API was only caught by scattered behavior tests.
- Upstream fixtures are an external git submodule: without it, every target collected
  zero suites and the test task simply failed (or, once made skippable, could silently
  collect nothing). There was no small in-repo fixture proving the per-target
  collection machinery itself works.
- Nothing verified that generated/derived sources match their inputs.

### Adjacent-semantics regression guards

- The registry wiring test uses the public `JsonSchemaLoader` with
  `ANNOTATION_AND_ASSERTION`, guarding against a format being registered under the wrong
  key or pointing at a non-asserting validator (valid **and** invalid samples).
- The optional fixture encodes the draft-2020-12 default (invalid format value still
  validates when format is annotation-only), guarding the most fragile adjacent
  behavior if a draft default is flipped.
- The draft-default check only inspects explicit `null, <behavior>` branches, so
  vocabulary-derived defaults (draft 2019-09/2020-12) are not forced into the
  pre-draft-7 behavior; instead the gate requires every draft that hard-codes a default
  to agree, and the runtime optional fixture pins the 2020-12 expectation.

### Most dangerous counterexample and its regression test

The single most dangerous failure is a **fixture silently collected on zero targets**:
if the fixtures directory is renamed, a target source-set stops compiling the gate test,
or per-target filesystem/env resolution breaks, the whole multiplatform suite goes
"green" while exercising nothing. Its regression guards are:

1. `:test-suites:verifyFixtureParity` — verified manually to fail with
   `target '<each target>' collected 0 common fixtures` when `fixtures/common` is
   absent;
2. `GateFixtureTest`'s explicit `shouldBeGreaterThan(0)` collection tests on every
   target (executed on jvm, js(Node) and macosArm64 during implementation).

The second-most dangerous is a **stale generated registry** (format added to the spec
but the committed registry not regenerated): `verifyGeneratedSources` fails with the
first differing line and the regeneration command, verified manually by mutating the
committed file. The third is **format-default divergence between drafts**: flipping
draft 7's `null` branch makes `verifyFormatRegistry` fail listing each draft config and
its default.