# Codex brief: market windows whose horizon has not elapsed yet

Status: Ready for Codex. Frontend wording only; no request or response shape changes.

## Backend change this follows

A market capture used to list a window newest first from the chain tip and mark it `COMPLETE` when the listing reached
the window's start. If the capture ran before the window ended (for example 97 s into a 4500-slot horizon), the listing
looked finished but held only the first minutes, and replay treated the rest of the horizon as "no trades".

Now the capture reads the confirmed chain tip (`getSlot`, commitment `confirmed`) before it lists a window. A window whose
`toSlot` the tip has not reached is not listed. It is saved with the new coverage **`HORIZON_NOT_ELAPSED`**, and the next
resume checks it again. The HTTP contract changes only in these values:

- **Window coverage** (`CaptureWindowResponse.coverage`, `CaptureCaseResponse.coverage`): new value `HORIZON_NOT_ELAPSED`.
  The case's `marketStage` is `DISCOVERY_PENDING` for it.
- **Market stage reason** (inspection `market.reason`): new code `MARKET_HORIZON_NOT_ELAPSED`, with
  `state: "INCOMPLETE"`, `canResume: true`.
- **Case blocker** (`CaptureCaseResponse.blocker`): new code `MARKET_HORIZON_NOT_ELAPSED`.
- **Operation result** (market operation `code`): new code `HORIZON_NOT_ELAPSED`. The backend's `message` already says
  when to retry, e.g. `A market window has not ended on chain yet, so it was not listed. Retry after
  2026-10-19T15:12:31Z (estimated); resuming then finishes it.` When the operation has recovery attempts left, it goes to
  `WAITING_RETRY` with `recovery.nextRetryAt` at that estimate and resumes on its own.

## Changes

1. `frontend/src/features/captures/captureReasons.ts`: add to `REASONS`:

   ```ts
   MARKET_HORIZON_NOT_ELAPSED: 'A market window has not ended on chain yet, so it has not been listed. Resume after the window end; the capture then finishes it.',
   HORIZON_NOT_ELAPSED: 'A market window has not ended on chain yet. The capture retries on its own after the estimated end, or resume it then.',
   ```

   Without these entries `reasonText` falls back to the lower-cased code, which works but reads poorly.

2. Optional: wherever window `coverage` is shown raw (`CaptureDetailPage.tsx` window table, `CaptureCases.tsx`
   "Market stage / coverage"), show `HORIZON_NOT_ELAPSED` as `horizon not elapsed`. Keep other values unchanged.

3. `frontend/src/features/captures/CapturesPage.test.tsx`: add one case whose market stage is
   `{ state: 'INCOMPLETE', reason: 'MARKET_HORIZON_NOT_ELAPSED', canResume: true }`. Assert that the new text renders and
   that the market Resume action is offered.

## Verify

```bash
cd frontend && npm run lint && npm test -- --run && npm run build
```
