# Agreed-strategy run preset on dip-entry v1

Accepted 2026-10-16 with the approved [console capture, run and results plan](../plans/20261016-console-capture-run-ux.md). The user accepted every recommended answer: `horizonSlots` stays at the 4,500-slot engine default flagged `MISSING`, the `DECIDED` cutoff keeps `SimulationConfig.mvp()`, and approving the `decu-dip` v2 engine is a separate decision.

## Context

The agreed strategy is `decu-dip` v2 ([spec](../strategy/decu-dip-v2-spec.md), updated 2026-10-11). Its engine is planned in a [draft plan](../plans/20261015-decu-dip-v2-replay.md) and does not exist; the only runnable engine is `dip-entry` v1 (`dip-paper-v2`). New console runs started from `SimulationConfig.mvp()` (15% dip, +30% / −15%), not the agreed values, and the form gave no hint which rules of the spec a run could not apply.

## Decision

- `GET /api/runs/preset` returns `decu-dip-v2-on-dip-entry-v1`: the spec mapped onto dip-entry v1 field by field, each with a status. Agreed: dip 20%, take profit +55%, stop loss −40%, notional 0.1 SOL (curve payment, fees on top), transaction cost 0.001 SOL (network fee not added), quote wrapped SOL. Measured differently: minimum market cap 10,000 USD, which v1 checks at the dip with `>=`, while the spec checks at the reference buy and strictly. Assumption: latency 1 slot. Engine default: max age 2 slots. Missing: the observation window, so the backend never chooses a value for it.
- The preset lists the 8 spec rules v1 cannot apply: P2 first-ever buy, P3 signal over 1.9 SOL, P5 measured at the reference buy, P7 30-second dip window, P8 15% entry slippage, P11 timed exit, Section 8 exit retries and `UNRESOLVED`, P13 one position including in-flight entries. It never claims them.
- The preset is code (`AgreedStrategyPreset`), pinned to the spec's YAML block by `AgreedStrategyPresetTest`. The backend never reads `docs/` at runtime.
- `GET /api/config/defaults` and runs without a config still use `mvp()`, so API and CLI callers and `DECIDED` cutoffs see no change. The console form loads the preset instead.
- `presetId` on `POST /api/runs` is validated and logged, not stored: storing it would change replay's public `SimulationRuns.start` contract. A run from the unchanged preset is recognisable because its config equals the preset, and it has the same run id and `configurationHash` as the same values sent in basis points.

## Alternatives

- **Change `mvp()` to the agreed values.** Changes every existing default run id, the `DECIDED` cutoff and the pinned sample checksums.
- **Read the spec file at runtime.** Couples a deployment to `docs/` and lets an edit change runs silently.
- **Store `presetId` in the configuration snapshot.** Needs an optional parameter on `SimulationRuns.start`; deferred until provenance beyond the config itself is needed.

## Consequences

- A run from the preset is an approximation of the agreed strategy, and the console says so through the field statuses and the unsupported-rule list.
- A capture cut off in `DECIDED` mode under the legacy defaults can end before the preset's outcome is decided; the capture recommendation carries caveat `CUTOFF_USES_LEGACY_DEFAULTS`.

## Revisit when

- The `decu-dip` v2 engine ships: the preset then points to it and the unsupported-rule list empties.
- An observation window for dip-entry v1 is agreed (the plan's Open Question 4).
