# Changelog

All notable changes to this repository are documented in this file.

## Unreleased

### Multiplatform gate: generated artifacts, spec fixture parity and format/API alignment

A single, offline-reproducible entry point unifies the verification of the three
things that previously could silently diverge across Kotlin Multiplatform
targets: generated keyword/format wiring, collected spec fixtures and the
exported format API.

- `./gradlew verify` from the repository root runs the complete gate:
  generated format registry drift check, binary API alignment (`apiCheck`),
  cross-target fixture collection parity, the host-grouped test suites and a
  final generated-artifact drift check. Sub-command failures propagate: a test
  task that fails or discovers zero tests leaves no report and fails the gate.
- The built-in `format` keyword registry
  (`json-schema-validator/.../internal/formats/GeneratedFormatsRegistry.kt`)
  is now generated from the validator objects in the same package by the
  `generateFormatsRegistry` task. `verifyFormatsRegistry` regenerates in memory
  and fails when the committed file is stale, missing, or no longer matches the
  validators. `FormatAssertionFactory` consumes only the generated map, so a
  new validator that is not registered, or a stale registry, cannot compile or
  pass the gate.
- New `gate-fixtures` module provides small, independently-runnable fixtures:
  `src/commonFixtures` must be collected on every target with identical counts;
  `src/targetFixtures/<family>` holds JVM/JS/wasmJs/native-only fixtures that
  are reported separately as target-specific and are not part of the parity
  check. Fixtures are embedded as generated Kotlin sources
  (`generateGateFixtures`), so no target filesystem/resource lookup is needed
  and collection is identical in every backend.
- Each target test writes a JSON report (`build/gate-reports/<task>.json`)
  containing collected common fixtures, target-specific fixtures and the
  per-draft default format behaviour matrix. `verifyGateFixtures` aggregates the
  reports, rejects zero common-fixture collection and any per-target difference,
  and prints a summary with the target-specific skips listed explicitly.
- Root `test` task aggregates exactly the targets executable on the current
  host: JVM, JS Node and wasmJs Node on every OS, plus the host-native family
  (`macosArm64`, `linuxX64/linuxArm64` or `mingwX64`). iOS simulator tasks are
  opt-in via `-Pgate.ios=true` because they require a full Xcode installation;
  the deterministic host set runs with the command line tools only. The same
  property also gates the iOS entries of the `macOsAllTest` convention group.
- `checkCleanWorkingTree` compares the working tree to the index and fails on
  changes inside generated output paths; it is the final `verify` step and
  rejects stale or uncommitted generated artifacts without special-casing any
  fixture name.
- Tests pinning the adjacent semantics:
  - `FormatDefaultBehaviorTest` pins the default `format` behaviour per draft
    (assertion for drafts 4/6/7, annotation-only for 2019-09 and 2020-12).
  - `GeneratedFormatsRegistryTest` verifies registry normalization and that
    every generated entry is reachable through the public `JsonSchemaLoader`
    API and never asserts on non-string values.

### How to run

- One command, wrapper and locked dependencies only:
  - first time / dependency warm-up: `./gradlew verify`
  - offline repeat (local and CI converge here): `./gradlew verify --offline`
- Individual locations:
  - JVM fixtures: `./gradlew :gate-fixtures:jvmTest`
  - JS fixtures: `./gradlew :gate-fixtures:jsNodeTest`
  - wasmJs fixtures: `./gradlew :gate-fixtures:wasmJsNodeTest`
  - native fixtures: `./gradlew :gate-fixtures:macosArm64Test`
  - cross-target aggregation: `./gradlew :gate-fixtures:verifyGateFixtures`
  - format registry regeneration: `./gradlew generateFormatsRegistry`

### Coverage gaps that existed before

- The `KNOWN_FORMATS` map in `FormatAssertionFactory` was hand-maintained:
  adding a validator object did not require adding the `format` mapping, and no
  check detected a renamed/removed mapping or divergence from the exported API.
- Nothing asserted that multiplatform tests actually collected the same inputs
  per target. A broken backend, a disabled test task or a source-set wiring
  mistake could silently collect zero tests on one target while another target
  passed; `failOnNoDiscoveredTests` covers "no classes discovered" but not
  "classes loaded but collected zero cases".
- Target-specific exclusions (e.g. optional/format spec suites that cannot run
  on a backend) were implicit in runner logic; there was no report separating
  "skipped because target-specific" from "missing because broken".
- Generated wiring had no clean/dirty signal: there was no single task that
  failed on stale generated code, and no offline entry point shared by CI and
  local runs.

### Most dangerous counter-example and its regression case

The most dangerous counter-example for a Kotlin Multiplatform JSON Schema
library is **a single target silently collecting zero common fixtures while the
build stays green** — for example because a source-set directory for one target
is mis-wired, a backend test runner discovers the spec class but resolves zero
cases, or the report delivery on one target is broken. Every other target
passes, CI is green, and releases ship a backend that validates nothing; the
failure is indistinguishable from "target-specific skip" until a user reports
it.

The regression case is the `gate-fixtures` zero-collection path: with a common
fixture set of `type`, `required` and `format`, each target must produce a
report listing exactly that set, and `VerifyGateReports` fails with
`target '<task>' collected zero common fixtures` when a report is empty or
missing. The failure was exercised by replacing a report with an empty
`commonFixtures` list; the gate fails on the zero target and on the resulting
parity mismatch, with the offending target named in the diagnostic. The same
harness guards the adjacent failure modes: differing collected sets (parity
error lists missing/extra fixtures), flipped per-draft format defaults
(`formatAssertionByDefault` mismatch), and stale generated registries
(`verifyFormatsRegistry` plus `checkCleanWorkingTree`).

### Adjacent semantics protected against regression

- Draft-dependent default format behaviour (assertion vs annotation-only) is
  pinned both per-target (report matrix) and in the library test suite.
- Unknown `format` values still pass schema loading (annotation semantics);
  the registry test exercises every registered name through the public API.
- Non-string instances must always pass format assertions; covered for every
  generated format through the public loader.
- Host grouping behaviour is unchanged when the new property is not set:
  without `-Pgate.ios=true` the iOS simulator tasks simply are not members of
  the host group, mirroring the pre-existing separation between macos/iOS and
  linux/windows groups.
