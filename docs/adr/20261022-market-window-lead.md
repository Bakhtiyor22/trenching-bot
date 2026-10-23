# Market windows start shortly before the wallet's first buy

Accepted 2026-10-22 with the approved [market window lead plan](../plans/20261022-market-window-lead-before-first-buy.md). The user accepted every recommendation: a lead of 50 slots fixed in code and recorded per manifest, no upgrade of existing captures, and replay unchanged for slot S. Amends [outcome-bounded market capture](20261015-outcome-bounded-market-capture.md).

## Context

A market window was `[S+1, S+4500]`, S being the slot of the tracked wallet's first buy of the mint. Discovery listed the mint's signatures with `until` set to that buy, so the buy, the other trades of slot S and everything before were never recorded. On real runs the first recorded trade, one slot after the buy, already sits about 40% above the buy's fill (8.8 SOL of buys landed in slot S after it), and the chart has to draw an "unrecorded" connector. A Pump.fun trade's reserves before it equal the previous trade's reserves after it, so every trade of slot S, the buy included, takes a proven place in the slot once it is captured.

## Decision

1. **The lead is in slots: 50.** Windows, coverage and replay are slot-based and block times are whole seconds, so a lead in time cannot be resolved exactly. 50 slots is about 10.6 s at the measured 212–216 ms pace. It is `MarketCaptureWindowParameters.DEFAULT_LEAD_SLOTS`, fixed in code, and recorded per capture in `windowParameters.leadSlots`; it is not a configuration property.
2. **One formula, legacy included.** `MarketCaptureWindow.forFirstBuy` computes `[S - leadSlots, S + horizonSlots]` and is the only place the window start is derived; `firstBuySlot` is its inverse (`fromSlot + leadSlots`). Manifest versions 2 and 3 read `leadSlots = -1` (`LEGACY_LEAD_SLOTS`), which is the old `S + 1`, so old captures need no migration and every check uses the same expression. A lead reaching before slot 0 is refused with `MARKET_WINDOW_INVALID`.
3. **The anchor stays the buy's signature but stops being a listing bound.** `untilSignature` keeps its JSON name and its role as the window's identity and anchor. A window with a lead lists with no `until`; the existing rule that an entry below `fromSlot` completes the window is the only lower bound. Legacy windows keep listing with `until`, so a resumed version 2 or 3 capture lists exactly as before.
4. **The lead only adds data before the decision point.** A capture cutoff may decide only from the first buy's slot on, never inside the lead, and is not asked while the captured prefix is still inside it. Replay keeps reading observations from S+1, so outcomes do not change.
5. **Manifest version 4.** A capture planned with a lead writes `market-manifest.json` version 4 with `windowParameters.leadSlots`, with or without a cutoff. A legacy capture keeps writing version 2, or 3 with a cutoff, byte for byte as before; an old artifact is never rewritten to a newer version. Version 4 must carry a lead of 0 to 1,000 slots (with the legacy value it is refused as `MANIFEST_VERSION`); versions 2 and 3 must not carry one.

## Alternatives

- **A lead in seconds.** Cannot be resolved exactly against whole-second block times, and every bound is slot-based.
- **A separate pre-window per mint.** Two coverage entries per pair break the dataset loader's one-entry-per-pair rule and double the window bookkeeping.
- **Record from token creation.** Unbounded: a token can be days old before the wallet buys it.
- **Upgrade existing captures in place** by listing older than their saved cursor. Left out; re-capture the cases that matter.

## Consequences

- New captures hold slot S in full, the buy's own `TradeEvent` included as an ordinary observation with the tracked wallet as trader, and about 10 s of market before it. The chart no longer needs the unrecorded connector for them.
- A window costs its lead's trades too: usually one listing page and tens of extra detail requests. Lead trades count toward `maxTransactions` and the request bound; a busy lead can make a window `OVER_CAP`, which is reported, never trimmed.
- In `DECIDED` mode a window can be decided only once its lead and slot S are captured, so a decision at S (for example `UNSUPPORTED_SUPPLY`) now follows the fetch batch that completes the lead and slot S, which need not be the first, instead of preceding any fetch.
- Lead trades are held to the same completeness rules as the rest of the window (plan rule 3): a detail gap, a blocking exclusion or a missing SOL/USDT minute inside `[S-50, S]` refuses export and qualification exactly like one after S, and in `DECIDED` mode keeps the window from being decided until it is filled.
- New datasets get new ids and checksums because their coverage and observations differ. Existing manifests, cases, datasets and runs load and replay unchanged.

## Revisit when

- The strategy should see trades in slot S after the buy (with latency 1 a dip there could fill in S+1); decide it with the `decu-dip` v2 engine.
- A different lead size is needed, or it should become a capture setting.
- Existing captures should be extended backward instead of re-captured.

## Amendment 2026-10-24: market manifest version 5

A capture now records, for each window whose coverage completes, the block time it was recorded through: `throughBlockTime`, the block time of the nearest produced block at or below the window's last slot, read once with `getBlockTime` (API usage purpose `BLOCK_TIME`) ([decision](20261024-timed-dip-window-and-exit.md)). It is written in `market-manifest.json` version 5, which keeps every version 4 field, the lead included. Versions 4 and earlier read it as null and are never rewritten; a failed read leaves it null and is retried on resume. The export carries it into dataset schema v6 as `coverage[].throughTime`, where it proves that a time exit's moment is covered on a token that stopped trading.
