# Standalone verification fixtures

This directory holds a small, self-contained set of JSON Schema Test Suite
compatible fixtures. They exist so the multiplatform verification gates can
run offline, without the `test-suites/schema-test-suite` submodule.

Layout (discovered dynamically, no file name is special-cased):

```
<draft>/*.json                      common fixtures, collected on every target
<draft>/optional/*.json             common optional fixtures
<draft>/optional/format/*.json      common format fixtures (run with format assertions enabled)
<draft>/target-specific/<family>/*.json
                                    fixtures collected only on targets of that
                                    family (jvm, js, wasmJs, native); skipped
                                    elsewhere and reported as target-specific skips
```

Each file follows the JSON Schema Test Suite format: an array of suites, each
with `description`, `schema` and a `tests` array of `{description, data, valid}`.
Schemas carry an explicit `$schema` so the loader can pick the draft.

The gates that consume these fixtures:

- `./gradlew verifyFixtureParity` — build-time collection parity across all
  host-runnable targets; zero collection of any common fixture fails the build.
- The `StandaloneFixtureGateTest` spec in `:test-suites` — executes every
  collected case through the validator on each target; runnable on its own via
  `./gradlew :test-suites:jvmTest --tests "*StandaloneFixtureGateTest*"`.
