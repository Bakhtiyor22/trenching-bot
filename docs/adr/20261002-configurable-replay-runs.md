# Configurable replay runs

Accepted 2026-10-02 with the [console plan](../plans/archive/20261002-console-and-monitoring.md), Key Decision 12.
Amends ADR 0001 only in who chooses the parameters; the simulation semantics are unchanged.

## Context

ADR 0001 fixed the MVP parameters: 100 quote units notional, 10 bps fee, 50 bps slippage, two-slot
wait and age bounds, exit after 5 slots, wrapped SOL as quote. Every run, CLI or persisted, used
`SimulationConfig.mvp()`. The console lets a user start a run on a stored dataset with different
values to compare outcomes.

## Decision

- A console run may set exactly the `SimulationConfig.of` parameters: `notional`, `feeBps`,
  `slippageBps`, `maxWaitSlots`, `maxAgeSlots`, `holdingSlots`, `quoteMint`. Their bounds are those
  of `SimulationConfig.of` (bps 0..9999, wait/age 0..1000, holding at least 1, notional positive
  at scale 12, a valid 32-byte base58 quote mint).
- `strategyName`/`strategyVersion` stay `confirmed-buy`/`1` and are not editable: behaviour comes
  from code, and a label must not claim a different strategy.
- The notional crosses the API as a plain decimal string (no JSON number, no exponent) and is
  normalized to scale 12, `HALF_EVEN`, as before.
- `mvp()` remains the form default and the only CLI configuration. A run request without a
  configuration uses it.
- Reproducibility is unchanged: the run id is `StableHash(engineVersion, datasetChecksum,
  configurationHash)`, so the same dataset and configuration always give the same run (returned as
  existing, not recomputed into new records), and a different configuration is a different run. The
  configuration is stored with the run's report.

## Alternatives

- **MVP parameters only.** Rejected: the console's purpose is comparing parameter choices on the
  same data.
- **Editable strategy identity or entry offsets.** Rejected: these change simulation semantics and
  need their own decision.

## Consequences

- Runs of one dataset can differ only by the listed parameters; their results are comparable and
  each is reproducible from its stored dataset and configuration.
- Strategy exposes the shared `SimulationConfigBounds`; the console uses them to report each bad
  field separately, and `SimulationConfig.of` still builds and re-checks every configuration.
- `simulation_runs.dataset_snapshot` intentionally remains alongside `replay_datasets.snapshot`.
  Keeping the original per-run snapshot preserves the existing ledger contract and schema; removing
  that duplication requires a separate approved schema change.

## Revisit when

A strategy version 2, editable entry offsets or exit rules, or a live (non-replay) execution mode is
proposed.
