# Paper-Trading Domain

The rules that make a simulation result trustworthy. A result that violates any of these is not a
bug in one number — it invalidates the research. Treat violations as BLOCKERs.

Source of the agreed model: `docs/WEEKLY_EXECUTION_PLAN.md` and the ADRs in `docs/adr/`. When a rule
below depends on a decision those documents have not settled, **ask** — see `ask-protocol.md`.

## Read-only toward Solana

The system observes a configured wallet and simulates trades on paper. It never submits anything.

- No transaction building, signing, simulation-with-signer or sending. No write RPC methods.
- No keypair, private key or seed phrase anywhere — code, config, fixtures, logs, docs.
- A future real-execution adapter is out of scope and gated behind a separate security review.

## The flow

```
wallet activity → normalized wallet trade → strategy decision → trade intents (+1/+2/+3 slots)
  → paper fill or explicit rejection → position → rule-based exit → realized P&L → persistence
```

The same orchestration runs in replay (fixture) mode and in live mode. Replay advances slots
deterministically; live mode waits for observations. Neither may take a shortcut the other cannot.

## Numbers

| Concept | Kotlin type | Postgres | Notes |
|---|---|---|---|
| Raw on-chain amount | `BigInteger` | `numeric(20,0)` | SPL amounts are `u64`; can exceed `Long` |
| Mint decimals | `Int` | `smallint` | Required for any arithmetic |
| Price, quantity, notional, fee, slippage, P&L | `BigDecimal` | `numeric(p,s)` | Explicit scale + `RoundingMode` |
| Slot | `Long` | `bigint` | Ordering authority in simulation |
| Time | `Instant` | `timestamptz` | UTC |
| Basis points / ratios | `BigDecimal` | `numeric` | Never `Double` |

Rules:

- **Never `Double` or `Float`**, not even for an intermediate. Parse vendor decimals from strings
  into `BigDecimal`, not via `Double`.
- Every `divide` and `setScale` names its `RoundingMode`. The scales and rounding modes are a
  recorded model choice — keep them in one constants `object` per concept, not scattered.
- Compare with `compareTo` / `signum`. `BigDecimal("1.0") != BigDecimal("1.00")` under `equals`.
- Convert raw amount ↔ decimal quantity in exactly one place per module, with the mint's decimals.

## Slots and time

- Slot is the ordering key for events, observations, entries and exits. Wall-clock time is
  metadata.
- Entry offsets are relative to the source event's slot: `targetSlot = sourceSlot + offset`.
- Out-of-order input is sorted deterministically (slot, then a stable tiebreak such as source
  event id), never by arrival order.
- The commitment level an event was observed at is recorded with it.

## Determinism

The same dataset + configuration + code version must produce the same persisted result and the
same result checksum.

- Inject `Clock` and an id generator; never call `Instant.now()`, `UUID.randomUUID()` or an unseeded
  `Random` in domain or application code.
- Prefer ids derived from the input (source signature + instruction index + strategy version +
  offset) over random ids where identity must survive a replay.
- Never iterate a `HashMap`/`HashSet` where order affects the output — sort or use an ordered
  collection.

## Prices and fills

- A fill references the **exact market observation** it used.
- The observation must satisfy the agreed eligibility rule (for example first observation at or
  after the target slot, within a maximum wait). Anything else is an **explicit rejection** with a
  reason — missing, stale, out of window, insufficient liquidity.
- **No silent fallback**: no interpolation, no "latest known price", no widening the window on the
  fly.
- Fee, slippage and priority-fee assumptions are recorded on the fill, even when they are zero.
- Where only midpoint or last-trade prices exist, the fill is flagged with that assumption.

## Positions

- An explicit state machine (for example `OPEN → CLOSED`); illegal transitions fail with a typed
  outcome or exception, never silently.
- A position closes **once**, with a close reason. Competing exit triggers in the same slot resolve
  by the agreed tie-break order.
- Realized P&L is computed in the agreed P&L currency at the agreed scale and is reproducible by
  hand from the stored fill and exit data.
- Concurrent updates are guarded (optimistic `@Version` or a unique constraint), not by hoping.

## Idempotency

Live delivery is never exactly-once, and replay runs the same events again.

- Source events are unique by their external identity (signature + instruction index).
- Intents are unique by strategy + version + source event + offset; one fill per paper order; one
  lifecycle per position.
- Enforce each at **both** the application boundary (return the existing result) and the database
  (unique constraint). A retry after a crash must converge to the same state.

## Provenance

Every stored simulation record carries enough to explain itself:

- simulation run id, source event id, strategy name and version, configuration snapshot/hash,
  entry-slot offset;
- price source, commitment, fee/slippage model;
- for a fill, the observation id; for a close, the reason and the observation id.

A strategy result without provenance is invalid.

## Expected outcomes are values, not exceptions

Use sealed types for the finite outcomes of the domain:

```kotlin
sealed interface FillOutcome {
    data class Filled(val fill: PaperFill) : FillOutcome
    data class Rejected(val orderId: PaperOrderId, val reason: RejectionReason) : FillOutcome
}
```

Exceptions are for broken invariants and infrastructure failure — see `errors-and-logging.md`.
