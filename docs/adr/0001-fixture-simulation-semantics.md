# Deterministic fixture MVP

Accepted 2026-10-01. The user approved these simulation semantics in chat.

- Confirmed buys only. Sells do not force an exit. Failed/unknown transactions create no signal.
- Independent entry scenarios at source slot +1, +2, +3.
- First valid pair observation at/after target, up to two slots later inclusive; reject missing, stale, invalid-quality, or insufficient-liquidity inputs. Maximum observation age is two slots. Break equal-slot ties by stable observation ID.
- Fixed 100 quote units per entry; fixtures use a single explicit quote mint. Fees are 10 basis points of gross notional; slippage is 50 basis points adverse on entry and exit. Priority fee and modeled price impact are explicitly zero.
- Quantity, fee and quote P&L use scale 12, HALF_EVEN. Execution and exit prices keep scale 38 (revised 2026-10-02, engine `fixture-paper-v2`): scale 12 cut SOL-quoted memecoin prices of 1e-8..1e-11 to a few significant digits. Only swaps against the configured quote mint (wrapped SOL by default) create signals. Entry cost includes the fee in addition to the fixed notional. Exit gross proceeds use the rounded entry quantity; net proceeds subtract exit fees.
- Exit on the first eligible observation at/after opened slot +5, using the same two-slot wait and age bounds. Without that observation the position remains open, with an explicit exit-unavailable result.
- One open position per run/scenario/base-and-quote pair; a competing event is rejected. A closed position permits a strictly later-slot entry. Same-slot close/entry ties conservatively favor the existing lifecycle.
- Inputs are ordered by slot and stable identity; observations in a slot are available before entries at that slot. Exits use later observations only. No interpolation or unlimited last-price fallback.
- IDs derive from dataset/config/engine version and source identity. A repeated run has the same business IDs and checksum.
- Replay inputs are curated fixtures, not verified provider captures. Source wallet identity, supported production swap routes, RPC/market provider, retention policy and live scope remain unresolved. Fixture identity must not be presented as the Decu wallet.
- Local profile default retained from the current project settings (2026-10-02); production is selected explicitly. Flyway uses UTC timestamp versions for new migrations per CLAUDE.md. The original `V1__baseline.sql` remains unchanged for existing database compatibility.

Hand-calculated example (source slot 100): entry observations at 101/102/103 all price 10; exit observations at 106/107/108 all price 11. Each entry has execution price 10.05, quantity 9.950248756219, fee 0.100000000000, cost 100.100000000000. Each exit has execution price 10.945, gross proceeds 108.905472636817, fee 0.108905472637, net proceeds 108.796567164180, realized P&L 8.696567164180 quote units.

This is a fee/slippage model, not an executable quote: no pool impact, MEV, priority fees, route parsing or live timing guarantee. Revisit when actual slot-aware captures and supported protocols are available.

Update 2026-10-08: the [dip-entry strategy decision](20261008-dip-entry-strategy.md) supersedes the entry offsets, fill wait, holding exit and their parameters. Fees, slippage, quantities, rounding and idempotency stand.
