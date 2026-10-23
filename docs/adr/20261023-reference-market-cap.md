# The minimum market cap is checked at the wallet's buy

Date: 2026-10-23. Status: accepted. Engine `dip-paper-v3`.

## Context

The agreed `decu-dip` v2 spec (P5, Section 4 check 4) skips a signal unless the reference, Decu's first buy, is valued **above** $10,000. It does not check the dip: "A $12,000 reference qualifies, even though the entry level is then $9,600. This is intended."

`dip-entry` v1 did the opposite ([dip-entry decision](20261008-dip-entry-strategy.md)). It never valued the buy, and only let a dip trade trigger when that trade's market cap was **at least** the minimum, ending the cycle as `DIP_BELOW_MIN_MARKET_CAP` otherwise. With the agreed 20% dip, a dip can only reach $10,000 after a buy of about $12,500 or more, so every buy between $10,000 and $12,500 that the spec enters was never entered. The preset showed this as `MEASURED_AT_DIP_INCLUSIVE` and the unsupported rule `MCAP_AT_REFERENCE_BUY`.

## Decision

- **The buy is valued once, before the dip watch.** Market cap = P0 × 1,000,000,000 × the SOL/USDT rate of the buy's minute, at scale 12. The cycle ends as `REFERENCE_BELOW_MIN_MARKET_CAP` (watch outcome and rejection reason) unless that is strictly above `minMarketCapUsd`. It is decided at the buy's slot, like `UNSUPPORTED_SUPPLY`.
- **The dip is not checked.** The first eligible trade at or below the dip level triggers whatever its market cap. The trigger still records its own market cap and rate.
- **The buy's rate.** A capture with the [market-window lead](20261022-market-window-lead.md) records the buy as a trade, so its minute has a rate. A dataset recorded before the lead may not; the rate of the token's first recorded trade after the buy is used then. With neither, the buy cannot be valued and the dip watch runs, which, with no trade after the buy, ends as `NO_DIP`.
- **P0 stays the buy's average execution price**, as before; the spec's post-trade price is still the unsupported rule `POST_TRADE_PRICE`.
- **Engine `dip-paper-v3`.** Simulation semantics changed, so the engine version moves on. Every run id changes, and `DECIDED` market cutoffs recorded under `dip-paper-v2` are reopened when their capture resumes, since the cutoff identity includes the engine version. Configuration hashes are unchanged.
- **`DIP_BELOW_MIN_MARKET_CAP` stays** in both enums, so runs stored by `dip-paper-v2` still read; `dip-paper-v3` never gives it.

The preset's `minMarketCapUsd` becomes `AGREED`, and `MCAP_AT_REFERENCE_BUY` leaves the unsupported list, which now has 8 rules.

## Alternatives

- A configuration switch between the two checks: it would keep a rule nobody wants and double the cases to test.
- Checking both the buy and the dip: the spec says outright that the dip is not checked.

## Consequences

- `SimulationConfig.mvp()` and every console or CLI run use the new check, not only the preset.
- A stored `dip-paper-v2` run keeps its results. Running the same dataset and configuration again creates a new `dip-paper-v3` run.
- The new value is stored in `trade_intents.watch` and `paper_orders.rejection_reason` (30 characters, within their 32). The reference DDL in `docs/schema/` puts no value check on either column, so production needs no change. A local table that Hibernate created has a check listing the enum's values, which `update` never alters, so it refuses the new value (`trade_intents_watch_check`). [V20261023120000__reference_market_cap.sql](../schema/V20261023120000__reference_market_cap.sql) extends those checks in place; `docker compose down -v` also works but deletes the local data.
