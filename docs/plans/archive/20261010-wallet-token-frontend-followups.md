# Wallet + token console: frontend follow-ups (brief for Codex)

> Small UI follow-ups after the wallet + token capture backend fixes. Frontend only; the backend already sends everything below.

Status: Delivered

## Context

- The feature is planned in [20261010-wallet-token-capture.md](20261010-wallet-token-capture.md) and decided in [the ADR](../../adr/20261010-wallet-token-capture.md).
- The UI lives in `frontend/src/features/captures/`:
  - `TokenVerdict.tsx`: the "Token verdict" panel;
  - `CapturesPage.tsx`: the create form;
  - `CaptureDetailPage.tsx`;
  - `captureReasons.ts`: the code → label map.
- Types live in `frontend/src/api/captures.ts`; MSW fixtures in `frontend/src/mocks/captureFixtures.ts` and `captureHandlers.ts`.
- Follow the existing conventions: Mantine, the `ShortId` signature display, exact decimal strings, and backend flags drive enabled actions.
- Don't change backend files, other features, or `frontend/src/features/solana/SolanaPage.tsx`, which has the user's own uncommitted edit.

## Tasks

1. **Labels for the new codes** in `captureReasons.ts`. Unknown codes already fall back to a prettified code, so this is wording only.
   - `SAME_SLOT_BUY` (token-account first-buy gap): "Another buy of this token by the wallet is in the same slot as its earliest buy, and the order within a slot is not recorded, so neither can be called the first."
   - `FIRST_BUY_NOT_LISTED` (actual first-buy gap): "The wallet's Pump.fun accumulator listing did not include its known buy, so the provider's listing cannot be trusted to show earlier buys for this case."
   - `QUALIFICATION_PENDING` (capture reason, with capture state `COMPLETE` and stage `EXPORT`): "Dataset exported; qualification has not run yet or did not finish. Use Qualify."
   - `FIRST_BUY_MISMATCH` (replay failure category): "The replay started from a different buy than the proven first buy; this is a data problem, not a strategy outcome."
   - Error codes the QUALIFY request can return (HTTP 409), shown through the existing `CommandError`:
     - `EXPORT_CHANGED`: "The exported dataset changed or is missing since export. Export again, then qualify."
     - `EXPORT_REQUIRED`: "Export the dataset before qualifying."
     - `UNSUPPORTED_SCOPE`: "Qualification is only available for wallet + token sessions."
2. **Pending-qualification wording.** The `INCOMPLETE_CAPTURE` detail text in `TokenVerdict.tsx` currently says to complete the capture first. When `qualification.capture.reason === 'QUALIFICATION_PENDING'`, show the pending text from task 1 instead, and keep the Qualify action visible (it already shows when `datasetReady`). Never present this state as a negative case.
3. **Narrow-width layout of the verdict panel.** At a 375px viewport the verdict table measures about 948px. Signatures and the coverage claim (which embeds an 88-character signature) cannot wrap, and the enclosing Mantine `Card` has `overflow: hidden`, so the content is clipped.
   - Let long values wrap: `overflow-wrap: anywhere` on the claim text and full signatures.
   - Stack each label and its value vertically below the `sm` breakpoint.
   - Keep desktop as it is.
   - The rest of the detail page has narrow-width issues that predate this feature; leave them alone, and mention them in the report if they are visible.
4. **MSW fixtures:** add wallet-token sessions for:
   - pending qualification: capture `COMPLETE` / `EXPORT` / `QUALIFICATION_PENDING`, replay `NOT_RUN`, classification `INCOMPLETE_CAPTURE`, `datasetReady: true`;
   - `SAME_SLOT_BUY`: `tokenAccountFirstBuy` `NOT_ESTABLISHED`, `actualFirstBuy` `NOT_VERIFIED`, classification `FIRST_BUY_UNPROVEN`;
   - `FIRST_BUY_NOT_LISTED`: `actualFirstBuy` `UNPROVEN`, blocking = the buy's signature, classification `FIRST_BUY_UNPROVEN`.

   Keep the existing fixtures unchanged.

## Tests

- `TokenVerdict.test.tsx`:
  - each new label renders;
  - the pending state shows the pending text and the Qualify action, and no negative-case wording;
  - `FIRST_BUY_NOT_LISTED` and `SAME_SLOT_BUY` render their reasons and blocking signatures.
- A layout test is optional; at least assert that the claim element carries the wrapping style or class.

## Verify

```bash
npm --prefix frontend test
```

```bash
npm --prefix frontend run lint
```

```bash
npm --prefix frontend run build
```

Optionally run the mocked console (`npm --prefix frontend run dev -- --port 5174` with `VITE_USE_MOCKS=true`, then open `/console/captures`) and check a wallet-token session at 375px and at desktop width.

## Delivered

2026-10-10: shipped all seven reason labels, pending-qualification wording and badge, verdict-only responsive rows below Mantine `sm`, wrapping coverage claims and signature text, and the three additive MSW sessions. Qualify remains controlled by backend readiness and locking flags. Existing fixtures and other feature behavior are preserved.

The existing `CommandError` rendered only backend messages, so `CaptureShared.tsx` now adds guidance for the three qualification error codes while retaining server messages, field errors and request reconciliation. Replay `FIRST_BUY_MISMATCH` retains its category and adds the requested explanation.

Verification: test-first focused checks passed (24 tests), followed by the complete frontend suite (24 files, 152 tests), lint, production build and scoped whitespace checks. Tests/build used an ignored temporary Vite config with the original frontend options, explicit protected-file exclusions and environment-file loading disabled; the production Vite configuration was not edited. Review found no actionable defects. The initial optional browser check failed with `helper_unknown_error: setup refresh had errors`; the user-requested retry completed on 2026-10-11 (see below). Pre-existing detail-page narrow-width issues were outside this work.

No schema change, backend change, dependency change or configuration requirement. Documentation: this delivery note and the originating capture plan's follow-up link.
## Browser verification retry

2026-10-11: the mocked frontend was reachable after running its dev server outside the isolated process sandbox. The ignored temporary dev config disabled environment-file loading and backend proxies and enabled MSW explicitly; no live provider or backend was contacted.

The qualifying verdict was measured at a 375px viewport: labels and values were stacked (`display: block`); table client/scroll widths were both 283px, and coverage-claim client/scroll widths were both 259px with `overflow-wrap: anywhere`. At 1440px, rows retained `table-cell` layout and the desktop label's `white-space: nowrap`; table client/scroll widths were both 1131px. Screenshots confirmed the wrapped claim and exact decimal text were readable.

Pending qualification was also inspected in rendered narrow and desktop panes: the pending badge and guidance appeared, Qualify was enabled, and neither negative-case wording nor the instruction to complete the capture appeared. Saved pending-state screenshots use the panes' actual widths (276px and 1105px); those captures are additional evidence, separate from the 375px/1440px measurements. No implementation change or schema change was needed.