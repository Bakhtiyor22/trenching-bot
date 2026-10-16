# Codex brief: wallet + token captures complete only at the end of history

Status: Implemented. Frontend text, defaults, mocks and regression coverage match the backend contract. The wallet
Start/Resume action is hidden when inspection requires a new wallet attempt. No schema change.

## Backend change this follows

- The wallet sample cap (`bounds.walletLimitMax`) is now **1000** (was 100). The wallet + token form defaults
  (`walletTokenDefaults`) are now `limit: 1000, scanPages: 20` (were 100 and 5). The other scopes' defaults are unchanged.
- A wallet + token capture is now complete only when its token-account listing reached the end of the history
  (`discoveryStop=HISTORY_END`) and verification finished. A listing that stopped at its record or page limit finishes
  with the operation state `INCOMPLETE`, never `SUCCEEDED`. Older saved captures like this also read as incomplete.
- For such a capped capture, `GET` inspection returns this wallet stage:

  ```json
  { "state": "INCOMPLETE", "reason": "HISTORY_NOT_EXHAUSTED", "canStart": false, "canResume": false, "requiresNewAttempt": true }
  ```

  The decoded evidence is still returned: the inspection's decode counts, and the token verdict's
  `tokenAccountFirstBuy` = `NotEstablished` with reason `HISTORY_NOT_EXHAUSTED`, and `actualFirstBuy` = `NotVerified`.
  The verdict's capture state is `INCOMPLETE`, stage `WALLET`, reason `HISTORY_NOT_EXHAUSTED`.

The form already reads `walletLimitMax`, `walletPagesMax` and `walletTokenDefaults` from the config, so no logic change is
needed. `HISTORY_NOT_EXHAUSTED` is now also a wallet stage reason, so its text must make sense there too.

## Changes

1. `frontend/src/features/captures/captureReasons.ts`: replace the `HISTORY_NOT_EXHAUSTED` text, which still says
   "more than 100 records or 50 pages", with:

   > The token-account history was not read to its end: the listing stopped at the sample's record or page limit, so an
   > earlier buy cannot be ruled out. Resuming cannot finish this capture; start a new session with a larger sample or more
   > wallet scan pages.

2. `frontend/src/mocks/captureFixtures.ts`: in `captureConfig`, set `walletTokenDefaults` to
   `{ wallet: '', scope: 'wallet-token', limit: 1000, scanPages: 20, mint: '' }` and `bounds.walletLimitMax` to `1000`, to
   keep mirroring `CaptureController`/`CaptureValidation`.

3. `frontend/src/features/captures/CapturesPage.test.tsx`: the wallet + token creation test expects the request body
   `wallet: { ..., limit: 100, scanPages: 5, ... }`; update it to `limit: 1000, scanPages: 20`.

4. Add a test (e.g. in the capture detail page tests) that a wallet stage with reason `HISTORY_NOT_EXHAUSTED` and
   `canResume: false` shows the new text and no Start/Resume wallet button.

## Verify

```
cd frontend
npm test
npm run lint
npm run build
```

Verified: all 213 tests in 33 files pass; lint and production build pass. The full test suite required execution outside
the Windows sandbox after sandbox runs failed with temporary-file `ENOENT`/`EPERM` errors.
