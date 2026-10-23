# Outcome-bounded market capture and dataset coverage

Accepted 2026-10-15 with the approved [outcome-bounded market capture plan](../plans/20261015-outcome-bounded-market-capture.md). The user accepted every recommended answer: the cutoff decides under `SimulationConfig.mvp()`, a run beyond coverage is refused, `DECIDED` is the default only for console wallet-token captures, and dataset schema v5 adds coverage.

## Context

Market capture fetches every successful trade of a mint in `[S+1, S+4500]`, one request per trade: 2,000–15,000 requests and up to two hours per busy token. The [dip-entry strategy](20261008-dip-entry-strategy.md) with [bonding-curve execution](20261009-bonding-curve-execution.md) is causal: each decision reads only trades up to its own slot, and a cycle often ends (take profit, stop loss, migration) long before the horizon. Datasets also do not record which slots were observed, so a run with a longer horizon than the capture reads missing slots as "no trades".

## Decision

- Market details are fetched oldest first. Optionally (`DECIDED` mode), after each checkpointed batch the capture asks a cutoff, implemented by running the unchanged `ReplayRunner` on the slot-complete captured prefix, whether the window's outcome is decided. The window then records the decision slot X with the outcome, engine version and configuration hash. Later records are `NOT_NEEDED`.
- Each scenario has a decision slot: the close, fill or migration slot, or the horizon end for outcomes that need the whole window.
- Replay dataset v5 records each window's observed coverage. A run with a scenario decided beyond its coverage is refused (`MARKET_COVERAGE_ENDED`) and nothing is stored; v3/v4 datasets replay exactly as before.
- Capture-time decisions use unstored SOL/USDT rates from the existing source; export and qualification re-check with the recorded rates, so a wrong cutoff can only lead to "extend the capture", never a wrong result.
- A decided window can be extended to the full window from its saved listing.

## Alternatives

- **Stop at the tracked wallet's last sell.** Not a strategy rule: our exits are take profit, stop loss, migration or horizon, and can come after his.
- **A separate "is it decided" rule in the capture.** Duplicates the strategy and drifts from it.
- **Store a `COVERAGE_ENDED` outcome.** Changes engine outcomes and persisted rows, and lets a data gap look like a strategy result.
- **No coverage in datasets.** Leaves early-stopped data indistinguishable from "no trades".

## Consequences

- A decided capture costs roughly the trades up to the decision slot plus at most 99, instead of the whole window. After a migration, PumpSwap trades of the mint are no longer fetched once the outcome is decided.
- A decided dataset answers only configurations whose outcomes are decided within its coverage. Others are refused until the window is extended.
- The replay fixture schema gains v5; market manifests gain version 3 for decided captures; full captures stay byte-identical.

## Revisit when

- A strategy whose decisions are not causal over slots is added (for example one reading future trades).
- `decu-dip` v2 is approved and needs its own cutoff.
- Within-slot order is recorded, which would allow prefixes inside a slot.

## Amendment 2026-10-22: windows with a lead

New captures plan each window from 50 slots before the wallet's first buy, `[S-50, S+4500]`, list through the buy, and record the lead in market manifest version 4 ([decision](20261022-market-window-lead.md)). A cutoff is accepted only from the first buy's slot S on, never inside the lead; for version 2 and 3 captures S is the slot before the window's start, so their rule is unchanged.

## Amendment 2026-10-24: the agreed clocks and a four-minute minimum

The "Revisit when" condition for `decu-dip` v2 is met ([decision](20261024-timed-dip-window-and-exit.md), plan [timed dip window and exit](../plans/archive/20261023-timed-dip-window-and-exit.md)):

- **Configuration.** The cutoff and wallet + token qualification replay with `SimulationConfig.agreed()` instead of `mvp()`, so a cut capture is judged by the configuration that cut it. The evaluator identity is engine `dip-paper-v4` plus the `agreed()` hash; windows cut under `dip-paper-v3`/`mvp()` are reopened when a `DECIDED` capture resumes. A capture cut under `mvp()` that the agreed outcome needs past its cut is refused `MARKET_COVERAGE_ENDED` and can be resumed.
- **Four-minute minimum.** A window is decided only when its agreed outcome is decided within the prefix and the recording reaches `t0 + 240 s` (spec Section 11 `recordingWindowMs`), `t0` being the anchor buy's block time. Every token, even one skipped at its buy, keeps at least its first four minutes; a buy without a block time is never decided early. The window is cut at its outcome's slot or at the first slot proving the four minutes, whichever is later.
- **Proof of the recording's end.** The first prefix trade at or after `t0 + 240 s` proves it. Otherwise the capture reads the block time of the prefix end (`ChainCaptureReads.blockTime`, purpose `BLOCK_TIME`, within the request budget) only when a decision waits on it: the outcome is otherwise decided, or an open position waits for a time exit no trade proves. That time is also the prefix's coverage `throughTime`, so a time exit on a token that stopped trading can be decided. Reads are cached per slot within one capture invocation, and a window cut at that slot reuses the time as its `throughBlockTime`. A failed or empty read leaves the window undecided (`WARN market_capture cutoff block_time_unavailable`) and the capture continues. Captures that record no block times (manifest version 2, 3 or 4) never read one, so only a trade proves their four minutes.
- **Port.** `MarketCaptureCutoff.evaluate` gains `observedThroughTime: () -> Instant?`, a lazy read of the prefix end's block time, rather than an eager `Instant?`, so the time costs a request only when asked for. `marketdata` owns the port and `replay` implements it; nothing else is affected.

Consequences: a typical window now stops at about `t0 + 4–5 min` instead of `S + 4500`, at the cost of at most one block time read per checkpointed batch while a decision waits on time. A run with settings other than the agreed ones, including the legacy `mvp()` comparison, may need more of a window than a decided capture holds and is refused `MARKET_COVERAGE_ENDED`; `FULL` mode, or extending the decided window, serves those experiments.
