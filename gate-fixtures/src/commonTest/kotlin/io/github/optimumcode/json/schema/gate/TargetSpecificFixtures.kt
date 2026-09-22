package io.github.optimumcode.json.schema.gate

/**
 * Target-specific fixtures generated for the family of the current compilation target.
 * The actuals live in each family's test source set and are generated from
 * `src/targetFixtures/<family>`. They are NOT part of the cross-target parity check:
 * the gate only reports them as skipped-on-other-targets.
 */
internal expect fun targetSpecificGateFixtures(): Map<String, String>
