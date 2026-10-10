# Handoff: trenching-bot, 2026-10-10

Branch: feature/paper-trade. The working tree contains all four uncommitted features from Claude's 2026-10-09 handoff. No Git state changes were made during continuation.

## Current status

1. USDT display and market links: delivered.
2. Pump.fun user-buy discovery: delivered; the prior handoff records the user's 10 buys across three tokens.
3. Dip-entry strategy: delivered, with default notional 0.1 SOL.
4. Bonding-curve execution: all parts delivered, reviewed and verified. Engine dip-paper-v2 and dataset schema v3.

All four plans are now in docs/plans/archive/ with Delivered notes. README, ADR/format links, REAL_DATA_CAPTURE and IMPLEMENTATION_STATUS are synchronized.

## What continuation finished

- Verified the existing calibration service, decoder facts and deterministic report plumbing; registered the missing calibrateExecution Gradle task.
- Corrected calibration cost medians to exact BigDecimal values, retaining half-lamport medians and negative residuals. Signed per-buy error is scale 2 HALF_EVEN; summary median/max error uses absolute magnitudes.
- Added regression coverage for fractional medians, signed rounding ties, canonical same-slot selection, invalid reconstructed reserves, refunds, u64 balances/fees and report-file replacement.
- Independent review found an entry bug: curve completion during latency, or in the trigger slot, was missed because the search began after latency. FillModel now scans from the trigger, stops at completion, then filters by latency. Both new regression tests failed before the fix and passed afterward.
- Moved CurveExecution unchanged from papertrading.application to the public papertrading package; portfolio now imports the public contract.
- Fixed the export task description and README references from v2 to v3; corrected the ADR to require all sections of the existing production SQL.
- Corrected intermittent frontend test timeouts under concurrent jsdom load with a five-second async assertion budget and stronger URL quote-selection assertions. Production URL behavior already worked.

## Verification

- Final .\gradlew.bat build --offline --console=plain: SUCCESS; 496 tests, zero failures/errors/skips, including isolated PostgreSQL integration tests.
- Frontend npm test: 109 tests pass; npm run lint and npm run build pass.
- Calibration targeted checks: 79 tests pass; task discovery and calibrateExecution --args=--help pass.
- Review-fix targeted checks: 38 tests pass (FillModel, PositionService, ReplayRunner); compileKotlin passes.
- Sample P&L remains take profit +0.028260434000, stop loss -0.041748258000, total -0.013487824000 SOL.

## Remaining user-run steps

No live provider calls, local database resets, production SQL execution, backend restarts or real-capture calibration were performed by the agent.

1. Reset the local database before restarting the updated backend: old configuration snapshots and observations are incompatible. This deletes the local DB volume, so retain any experiments needed first.
   ```powershell
   docker compose down -v
   docker compose up -d
   ```
2. Restart the backend, then use Import sample dataset on Datasets to import dip-sample-v3.
3. For production, manually apply all of docs/schema/V20261009120000__bonding_curve_execution.sql before deploying: observation reserve/fee columns and checks, position close-cost columns/checks, and relaxed position-state/fill-fee constraints. Continuation introduced no additional schema change.
4. User-run offline calibration, with complete matching wallet and market captures:
   ```powershell
   $env:JAVA_HOME = 'C:/Users/user/.jdks/ms-21.0.10'
   .\gradlew.bat calibrateExecution -PwalletCaptureDirectory=build/captures/decu-pumpfun-buys -PmarketCaptureDirectory=build/captures/decu-dip-markets "-Preport=build/captures/execution-calibration.json"
   ```
   Read docs/REAL_DATA_CAPTURE.md for live market/rate capture and v3 export commands, if needed. The curve model requires no recapture just to obtain reserve fields already present in transaction artifacts; captures still need complete matching horizon windows.
5. Use measured fee/residual medians and latency error to propose a separate default-adjustment follow-up. Defaults remain transactionCostSol 0.001 and latencySlots 1. Residuals combine tips and rent/refunds, so they are not a direct network-fee estimate.

## Capture console (delivered 2026-10-10, later)

The [capture console plan](docs/plans/archive/20261010-capture-console.md) is delivered; see its Delivered section for review fixes, verification and known gaps. To use it, restart the backend with the local profile and the console enabled. SOLANA_RPC_URL must be configured in the backend process itself. Then open Captures in the console. Attach existing evidence by root-relative paths such as decu-pumpfun-buys and decu-dip-markets. Managed captures default to build/captures/console and are removed by gradlew clean. Set trenching.console.capture.root-directory to a directory outside build to keep them.

## V3 buy decoding (delivered 2026-10-10, later)

The [V3 decoder plan](docs/plans/archive/20261010-pumpfun-exact-quote-in-v3-decoder.md) is delivered with Amendment A (configurable capture deadline). To use it:

- Restart the backend and click **Inspect evidence** on the capture session; nothing needs recapturing. The 100-transaction sample now has 66 SOL-quoted buys across 15 tokens, so its market capture is large. Consider a 10–20 transaction session first.
- CLI capture runs accept `-PoperationTimeoutMinutes=<1..30>`. After `stopReason=DEADLINE`, re-run the same command to resume.

## Deleting captures and datasets (delivered 2026-10-10, later)

Both deletes work only from your machine and need confirmation in the console. Deleting a capture removes only the files the console created; attached CLI capture directories stay. Deleting a dataset keeps its runs; re-uploading the same file restores the dataset. Restart the backend to pick up the endpoints.

## Documented residual limit

CurveExecution.priceImpactBps uses the approved Int contract. Extreme synthetic reserve states satisfying only the basic invariants can yield impact beyond Int range and cause intValueExact to fail. A wider representation or explicit rejection rule needs a follow-up decision; no clamping or new rejection semantics were introduced.

## Standing restrictions

- Never read, inspect, search, copy, print, write or edit .env, .env.* or *.env files (including templates), secret/key material, or secret environment values such as SOLANA_RPC_URL.
- Solana is strictly read-only: no transaction construction, signing, simulation or sending.
- Never call live RPC/provider/Binance/Telegram, including through local backend endpoints. The user runs live captures.
- Ask before editing application-local.yml, application-prod.yml or Compose; running bootRun/psql/Compose; or resetting a database.
- Do not stop the user's backend on 8081, PostgreSQL on 5433, or Vite on 5173.
- Never run two Gradle builds together. Set JAVA_HOME=C:/Users/user/.jdks/ms-21.0.10 before Gradle; quote dotted -P values in PowerShell.
- No Git state changes unless the user asks. Commits, when requested, use short conventional messages without AI attribution.
- Follow AGENTS.md, CLAUDE.md and .claude/skills/kotlin-spring-guidelines/. Entities remain schema authority; production SQL is applied manually.
- A new feature requires an approved plan. Do not invent a decision for the residual limit above.
