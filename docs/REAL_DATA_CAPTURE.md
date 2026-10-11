# Real wallet history capture

This is the first stage of the [approved real replay plan](plans/archive/20261003-real-wallet-replay.md), as changed by its Amendment A ([successful-reference discovery](adr/20261003-successful-reference-discovery.md)). It records a bounded mainnet sample of successful transactions, verifies its evidence offline and decodes one verified route, the Pump.fun bonding-curve buy (see [Decoding](#decoding)). A second command captures the same-mint market trades around each decoded buy (see [Market capture](#market-capture)). A third, offline command exports both into a replay dataset the console can import and run (see [Export](#export-to-a-replay-dataset)).

## Capture from the console

Use **Captures** in the local console to manage the same evidence and replay workflow from the browser. The backend must run with the local profile and console enabled. Acquisition uses the backend process's SOLANA_RPC_URL setting; an environment value configured only in a separate capture terminal is not automatically available to the backend. The browser shows configuration availability and never asks for or returns an endpoint or credential. Reference-rate acquisition uses the backend's enabled reference-rate source.

1. Create a named capture session with the wallet and discovery scope. The initial UI sample is one successful reference through the Pump.fun user accumulator. To check one known token, use the [Wallet + token](#wallet--token-capture) scope instead. Scope, sample size and wallet scan pages are fixed when acquisition starts. Advanced settings show the effective budgets before each operation.
2. Alternatively, attach the existing wallet directory and optional market/rates directories using paths relative to the capture root, such as decu-pumpfun-buys and decu-dip-markets. Inspection verifies each stage offline, including valid incomplete captures. Original evidence is retained in place. Mismatching or tampered evidence does not unlock downstream acquisition/export.
3. Explicitly start wallet acquisition, inspect the result, and start market acquisition when supported buys are available. The market preset is 50 pages per window and capacity for 50000 transactions, with 400 requests and 100 MiB per operation. Capacity is a ceiling, not a target. The CLI defaults remain unchanged.
4. If request/byte budgets, the deadline or a provider interruption stops acquisition, the latest operation shows why (for example `Stopped: DEADLINE`), and Resume continues the same capture directory with a fresh per-operation allowance. Advanced settings also set the operation deadline, 1–30 minutes (default 10). Budget for this run and Total used for this capture are separate. For incomplete listing coverage, increase market scan pages within its bounds. An OVER_CAP result requires a new market attempt with an appropriate capacity; repeatedly resuming cannot change its frozen transaction cap.
5. Once every market window passes the same completeness/matching/gap/exclusion checks as export, start rate acquisition. A rates capture belongs to that market attempt's exact observation-minute set. Resume fetches missing minutes only. A mismatching attached rates capture needs a fresh rates attempt.
6. Export the verified dataset and download its canonical v3 JSON, or import the successful export into Datasets and follow its New run link. Repeated import uses the same checksum identity. A failed database import can retry without acquiring data again.
7. Calibrate execution from the verified wallet and market captures to inspect exact curve math, latency errors and fee/residual medians and download the deterministic report. Rates are unnecessary for calibration. The report does not change simulation defaults.

Only one console operation runs at a time. Refreshing or navigating away does not start another operation. Status polls read saved metadata, and after backend restart unfinished work is marked interrupted; resuming is explicit. Inspection, export, calibration and catalog registration make no provider calls. The application-wide SOL ticker retains its separate current-rate polling.

A new market attempt retains the wallet evidence and all prior attempts, comes with its own empty rates attempt, and clears the current export/calibration/dataset readiness. A new rates attempt keeps market and calibration results while clearing current export/dataset readiness. Previously imported datasets remain available in Datasets. New attempts allocate new directories; they never overwrite an old capture or automatically start acquisition. Creating a session or an attempt queues the offline inspection, so stage states are current without clicking Inspect evidence; a new session skips it while another capture runs.

Managed sessions are stored under build/captures/console by default. The validated trenching.console.capture.root-directory property changes the evidence root for both managed sessions and attachment paths. Use an ignored location outside build for evidence that must survive clean. Capture routes are available only locally; they are not available through the public preview tunnel. Delete removes a session and every file the console created for it, including managed evidence and generated exports and calibration reports. Attached directories are never touched, and imported datasets stay in Datasets. Existing CLI commands below remain supported.

## Wallet + token capture

Console only; there is no CLI option for this scope, although CLI inspection, market capture and export read its directories. Use it when you already know a wallet and one token and want to know whether the dip-entry strategy would have qualified on the wallet's first buy of that token. See the [decision](adr/20261010-wallet-token-capture.md) and the [plan](plans/archive/20261010-wallet-token-capture.md).

**Inputs.** Create a session with the scope **Wallet + token**, the wallet and the token's mint (a base58 address). The form defaults are 100 records and 5 listing pages for the token account, with 1,000 requests and 100 MiB per wallet operation. The other scopes take no mint.

### Which first buy the strategy needs

The dip-entry strategy starts one cycle at the wallet's first accepted buy of a token, and takes P0 from that buy ([dip-entry decision](adr/20261008-dip-entry-strategy.md)). The buy it needs is therefore the wallet's **first Pump.fun bonding-curve buy of the mint, by any curve route**, whether or not the decoder can price that route. A later recognised buy is never used instead. Purchases on other venues, such as a third-party pool before migration, are outside the strategy's definition and are not looked for.

The console keeps two results apart and never shows one as the other:

- **Earliest buy through token account** (token-account first buy): the earliest decoded buy among the transactions listed for the wallet's associated token account for the mint. It proves only what happened through that account.
- **Actual first buy**: the buy the strategy needs, whichever token account received the tokens.

Market capture is offered only when the actual first buy is proven and is the same transaction as the token-account first buy.

### Step 1: the token account

One `getAccountInfo` on the mint records its token program (Token or Token-2022; anything else, or a missing mint, stops before any listing). The wallet's token account is derived from the wallet, that program and the mint, and its signature listing is scanned newest-first like the other scopes (1–100 records, up to 50 pages). Because the listing is by address, it still answers after the account was closed.

- **Found**: discovery reached the end of the account's history and the earliest decoded buy credited the account, with no other decoded buy in its slot and no unrecognised acquisition at or before its slot.
- **No buy**: the history is complete and holds no buy of the token through this account. This is a valid answer, not a gap.
- **Not established**, with the reason and the blocking signatures: `HISTORY_NOT_EXHAUSTED` (more than 100 records or more pages than allowed; a reported limitation), `SAME_SLOT_BUY` (another decoded buy of the token in the earliest buy's slot; the order inside a slot is not relied on, so neither can be called first), `EARLIER_UNDECODED_ACQUISITION`, `EARLIER_UNKNOWN_RECORD` or `NON_ATA_CREDIT`.

### Step 2: the actual first buy

Step 2 runs only after **Found**, as part of the same wallet operation, and stops and resumes on budgets and deadlines like discovery.

1. **Creation.** If the buy's own transaction is the Pump.fun `create` or `create_v2` of this mint (the wallet created the token in its first buy), the buy is its own creation: the creation slot is the buy's slot and the mint is not listed at all. Otherwise the mint's listing is scanned back from the buy to the end of its history, then its oldest successful entries are fetched until one is the `create` or `create_v2` of this mint (at most 10 fetches). Transactions older than the creation are ignored: the token could not be bought on a curve that did not exist yet.
2. **Coverage date.** The creation must be after 2025-08-01 11:31 UTC, when Pump.fun confirmed that every buy passes the buyer's volume accumulator.
3. **Accumulator scans.** The wallet's Pump and PumpSwap user volume accumulators, both derived from the wallet, are each scanned from the head of their own listing (the newest entry) down to below the creation slot. Every recognised buy passes the Pump accumulator, so the Pump scan must list the buy itself; if it reaches below the creation without listing it, the provider's listing cannot be trusted for this case and the buy is **Unproven** with `FIRST_BUY_NOT_LISTED`.
4. **Entries.** Only after both scans are complete, the successful entries between the creation slot and the buy's slot are fetched once each, oldest first. An entry without the mint is irrelevant; an earlier decoded buy into another token account gives **Outside token account** as soon as nothing still unfetched is older or in the same slot, and no further entry is fetched; an earlier undecoded acquisition or an entry without evidence leaves the buy **Unproven**. Any other entry in the buy's own slot blocks, because the order inside a slot is not relied on.

Step 2 has shared caps: **50 pages per listing** (mint, Pump accumulator, PumpSwap accumulator) and **500 transaction fetches** in total, creation fetches included. An earlier buy into another token account is reported even when a later entry would have reached the fetch cap. A capped scan is `FIRST_BUY_UNPROVEN` with reason `VERIFICATION_BOUND`. The console shows which cap was reached and, per listing, the pages and entries scanned, the fetches used and the oldest slot reached. A capped result is never shown as complete and never as a negative case; it means the scan stopped before it could prove anything.

### What "proven" means

A proven buy is always shown with its claim, word for word: *actual first Pump.fun curve buy proven for documented routes, for a mint created after 2025-08-01 11:31 UTC*, with the creation transaction and slot and the accumulator scans that reached below it. Four things the public evidence cannot settle limit that claim:

- **G1, enforcement.** The accumulator requirement is documented by Pump.fun's published interfaces and announcements. That old buys could not omit it is the team's statement, not something observed here.
- **G2, unpublished instructions.** A buy instruction that appears in no published interface and skips both accumulators would be missed. None is known.
- **G3, other venues.** Buys outside Pump.fun are not part of the strategy and are not checked.
- **G4, provider index.** The end of a listing trusts the provider's signature index, as every capture scope does.

### Request usage (standard RPC)

| Part | Requests |
|---|---|
| Step 1 | 1 genesis + 1 `getAccountInfo` + 1 listing page + 1 detail per successful token-account record; typically about 5–25 |
| Creation | 1 page per 1,000 mint entries before the buy, failed ones included, plus about 1 fetch. A buy at launch needs 1 page; a buy an hour into a busy token about 30–60; a buy several hours in on a busy token reaches the cap |
| Accumulator scans | 1 page per 1,000 entries of the wallet's accumulator activity from now back to the creation, at most 50 pages each, plus 1 fetch per wallet Pump/PumpSwap transaction between the creation and the buy. Because the scans start at the head, checking an old buy of a very active wallet can reach the page cap |
| Market, rates | Unchanged; the market stage still dominates |
| Qualification | 0 |

### Verdict

Qualification does not screen a token before the market capture: the dip condition needs the trades of the 4,500-slot window, which only the market stage provides. After a successful export, the console queues the offline qualification automatically as the next operation; it replays the exported dataset with the default strategy configuration, makes no provider call and gives the same result for the same dataset. Before that, the verdict comes from inspection alone and the replay row shows `NOT_RUN`. Between the export and its qualification the Capture row shows `COMPLETE` at stage `EXPORT` with reason `QUALIFICATION_PENDING`; if the automatic qualification could not be queued or failed, it stays pending and **Qualify** runs it. Qualify can also be started again on the current export. Before replaying, the console checks the dataset file against the checksum recorded at export; a changed or missing file is refused with `EXPORT_CHANGED`, and the dataset must be exported again.

The verdict panel shows these rows: **Capture** (`COMPLETE`, `INCOMPLETE` or `UNUSABLE`, with the stage and reason), **Earliest buy through token account**, **Actual first buy** (with its claim, or its reason, cap and scanned counts), **Dip condition** (`QUALIFIED` with the trigger, `NOT_QUALIFIED` with the watch outcome, or `NOT_EVALUATED`), the entry and exit when the dip triggered (filled or not, closed with its reason and realised P&L, or still open), and **Replay** (`RAN`, `FAILED` with a category, or `NOT_RUN`). Prices and P&L are exact decimals from the replay report.

| Classification | Meaning |
|---|---|
| `QUALIFYING_DIP` | The dip condition triggered for the wallet's first buy |
| `NEGATIVE_CASE` | The replay ran and the dip condition did not trigger, or no buy started a cycle (dip `NOT_EVALUATED` with the first ingestion reason, such as `NOT_SIGNER`) |
| `INCOMPLETE_CAPTURE` | A stage is incomplete, the dataset is not exported yet, or its qualification is pending |
| `FIRST_BUY_UNPROVEN` | The actual first buy could not be proven, including a capped verification |
| `FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT` | An earlier buy went to another token account; it is reported and capture stops |
| `NO_TARGET_BUY` | The token account's complete history holds no buy of the token |
| `REPLAY_FAILED` | The replay of the export failed (category `DATASET_UNREADABLE`, `DATASET_INVALID`, `MULTIPLE_INTENTS`, `FIRST_BUY_MISMATCH` when the cycle started from another buy than the proven first buy, or `REPLAY_ERROR`); the capture keeps its own state |

Only `NEGATIVE_CASE` is a negative strategy result. An incomplete, unproven, outside or failed case says nothing about the strategy. A replay failure still completes the qualification operation; the operation itself fails only when the console cannot run it.

## Configure the user-run capture

The console and command are different processes. A `SOLANA_RPC_URL` set in an IDE backend run configuration is not automatically present in a new terminal. The command does not load `.env` files. Supply the full Helius Mainnet HTTP RPC URL in the environment of the terminal that runs capture. Keep it out of chat, shell command history, files, screenshots and logs.

For PowerShell, use a hidden prompt locally:

```powershell
$rpcInput = Read-Host "Paste your full Mainnet RPC URL" -AsSecureString
$env:SOLANA_RPC_URL = [System.Net.NetworkCredential]::new('', $rpcInput).Password
Remove-Variable rpcInput
$env:JAVA_HOME = 'C:/Users/user/.jdks/ms-21.0.10'
```

Do not print the environment variable. The capture process consumes it without exposing the endpoint. No browser variable, provider SDK, wallet connection or private key is needed.

## Capture the selected sample

Only the user runs this command against a live provider. The default build and test tasks never run it. It does not start the backend, bind a port or connect to PostgreSQL, so the existing backend can stay running.

```powershell
.\gradlew.bat captureWalletHistory -Pwallet=4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9 -Plimit=100 -PscanPages=20 -PcaptureDirectory=build/captures/decu-mainnet-successful -PrequestBudget=400 -PbyteBudget=104857600
```

From Git Bash, first `export JAVA_HOME="C:/Users/user/.jdks/ms-21.0.10"`, then use `./gradlew` with the same task/properties. Run one Gradle command at a time.

The per-command request budget bounds provider reads, including retries; the per-command byte budget here is 100 MiB. The manifest retains cumulative acquisition usage across resumes. Capture uses one request at a time with conservative rate limiting. Requests are not a billing-credit estimate: verify your account entitlement and remaining budget locally. No automatic paid upgrade or second-provider fallback occurs.

The command verifies mainnet genesis, then selects the latest `limit` (1–100, default 100) distinct **successful** transactions referencing the wallet, at confirmed commitment. It scans the wallet's signature listing newest-first in pages of 1,000 entries. Entries whose listing reports an error are skipped: their details are never requested or stored, and the audit keeps only how many were skipped. Successful transactions signed by other accounts are still selected and recorded as `NON_SIGNER`, so the audit shows how many selected transactions the wallet actually signed.

The scan is bounded by `-PscanPages` (`--scan-pages`, default 20, maximum 50, so at most 50,000 listing entries). Discovery stops at the first of:

- `LIMIT_REACHED`: `limit` successful references were selected. When that happens mid-page, scanning stops at that entry, and the scanned and skipped counts include entries only up to it.
- `HISTORY_END`: the listing returned a short or empty page.
- `SCAN_BOUND`: the page bound was reached. With fewer than `limit` selected, this is a completed discovery of the bounded scope, not a full history.

A default run costs at most 1 genesis request + 20 listing pages + 100 detail requests = 121 requests, within the default 400-request budget. Each listing page is also bounded by the 8 MiB response cap and the byte budget.

### Scope: the wallet's own Pump.fun buys

By default (`-Pscope=wallet-references`), discovery lists the wallet itself, so it also finds every transaction that only mentions the wallet. For Decu's wallet those are mostly other accounts' bots: the latest 100 successful references held 1 and then 0 transactions the wallet signed.

`-Pscope=pumpfun-user-buys` lists the wallet's Pump.fun **user volume accumulator** instead. That account is derived offline from the wallet (for Decu, `Hj2hjN1EAe5pv676kUMf14zbj1ioWEHaiukbHDqw2P3D`), and Pump.fun's `buy` passes it for its user, so its listing holds the wallet's own Pump.fun activity. See the [user-accumulator discovery decision](adr/20261006-pumpfun-user-accumulator-discovery.md). Everything else works exactly as above: successful entries only, the same bounds, budgets, cursor, resume and checks. For a first multi-buy dataset, use a small limit and new directories:

```powershell
.\gradlew.bat captureWalletHistory -Pwallet=4vw54BmAogeRV3vPKWyFet5yf8DTLcREzdSzx4rw9Ud9 -Pscope=pumpfun-user-buys -Plimit=10 -PscanPages=20 -PcaptureDirectory=build/captures/decu-pumpfun-buys -PrequestBudget=400 -PbyteBudget=104857600
```

Every decoded buy becomes one market window, and a window's cost grows with its mint's trading since the buy (see [Cost](#cost)), so run the market capture straight after. In this scope:

- a listed transaction that does not contain the wallet is recorded as `WALLET_ABSENT`: a resolved gap with no artifact;
- a transaction without the listed accumulator contradicts the listing and is `INVALID_TRANSACTION`;
- Pump.fun sells, claims, buys on curves quoted in another token, or instructions the decoder does not support that pass the accumulator are captured and decode as `UNSUPPORTED_ROUTE`.

The scope only reaches buys made after Pump.fun introduced the accumulator, and only Pump.fun. A resume must use the same scope.

The selection, the counts of scanned and skipped-failed entries, and the last *scanned* entry (the scan cursor) are saved after every listing page, including a page that held only failed entries. Discovery stores only each selected signature and its slot, the cursor's signature and slot, and the two counts; signature memo text is never saved. If the provider fails or a budget runs out mid-scan, running the command again with the same directory continues before the saved cursor. It never rescans saved pages or selects newer transactions. The wallet, `limit` and `scanPages` must match the saved capture. Use a new directory for a new research sample.

The manifest format is version 3. It records the scope and the listed address; inspection derives the address again and refuses a mismatch (`LISTING_ADDRESS`). Version 2 manifests, from before scopes, are still read as the wallet-reference scope, and a resumed one is saved as version 3. Directories captured before successful-reference discovery (version 1) are refused for both resume and inspection with category `MANIFEST_VERSION`, reported as `ARTIFACT_OR_CONFIGURATION_INVALID`. Capture into a new directory, for example `build/captures/decu-mainnet-successful`. Do not manually edit artifacts or the manifest. If a run is interrupted after writing a transaction file but before saving the manifest, the next run fetches that signature again and replaces the unreferenced file.

Only one capture can write to a directory at a time. The command holds an exclusive lock on `.capture.lock` inside the capture directory. A second capture into the same directory fails before any provider request, reported as `ARTIFACT_OR_CONFIGURATION_INVALID`. The lock file can remain after the command ends; it contains no data and inspection ignores it.

Legacy, version 0 and version 1 transactions are captured: details are requested with `maxSupportedTransactionVersion` 1, the same value the console probe uses. Mainnet carries version 1 transactions; a capture limited to version 0 recorded all 100 of this wallet's latest references as unsupported. The provider adds `transactionIndex` to the result and, for version 1, `transactionConfig` (priority fee, compute unit limit, loaded accounts data size limit, heap size) to the message. Both are type-checked like the other fields. Later versions are recorded as an explicit `UNSUPPORTED_VERSION` gap with no artifact.

`UNSUPPORTED_VERSION` is a resolved outcome, so a resumed capture does not fetch those transactions again. After a change to supported versions, capture into a new directory.

If any provider response repeats part of the configured endpoint, the whole response is discarded as a non-retryable provider failure and nothing from it is written. This covers the full URL and any path segment or query value of at least 8 characters, in raw or decoded form, compared case-insensitively. The capture then stops with incomplete status, and a rerun stops at the same point. Report the printed category and signature, never the payload.

## Inspect offline

```powershell
.\gradlew.bat inspectWalletHistory -PcaptureDirectory=build/captures/decu-mainnet-successful
```

Inspection needs neither RPC credentials nor PostgreSQL. It verifies artifact paths, hashes and selection consistency, including the discovery counts, cursor and stop reason, before presenting the audit. Both commands print `scope=LATEST_SUCCESSFUL_WALLET_REFERENCES` (or `scope=LATEST_SUCCESSFUL_PUMPFUN_USER_ACCUMULATOR_REFERENCES`) with the wallet, commitment and `listingAddress=`, and a line with `scanned=`, `skippedFailed=`, `scanPages=` (the page bound) and `discoveryStop=` (`NONE` while discovery is incomplete). Valid missing/null transaction responses are recorded as unavailable; provider interruptions leave incomplete acquisition status.

Every selected transaction was successful in the listing. If its fetched detail nevertheless reports an error, whether a named error string such as `AccountInUse` or a structured instruction error, the detail contradicts discovery. It is recorded as `INVALID_TRANSACTION`, nothing is stored and acquisition stays incomplete. The `FAILED` count is therefore expected to be 0. Capture records successful signer transactions as `UNCLASSIFIED`; inspection then decodes them (see [Decoding](#decoding)). The audit does not invent pairs, token amounts or successful trades.

Every transaction is checked against the RPC's `jsonParsed` field types: slot, block time, version, signatures, account keys, instructions, recent blockhash, error, fee, balances and, when present, token balances, inner instructions, log messages and the other metadata fields. A response with the wrong slot or signature, without the wallet among its account keys, or with any field of the wrong type is recorded as `INVALID_TRANSACTION`. Nothing is stored for it and acquisition stays incomplete.

Some transactions carry public on-chain text, such as memos, log messages or parsed instruction fields, that contains a link or a credential-like keyword (`api-key`, `authorization`, `bearer`). These are recorded as `WITHHELD_UNSAFE_TEXT`. The audit keeps their signature and slot, but the raw transaction is not stored. This is a resolved, explicit gap: retrying cannot change it, and capture continues to the next signature.

The sample covers successful transactions referencing this wallet, not all historical current/closed associated token accounts. It is up to 100 successful transactions within the scanned range, not necessarily 100 trades or 100 transactions the wallet signed. A completed acquisition can still have explicit unavailable, unsupported-version or withheld-text data gaps. Invalid or malformed data keeps acquisition incomplete.

## Decoding

When the capture is complete, `inspectWalletHistory` also decodes it offline. It first repeats the full verification above, then decodes the verified artifacts. Decoding refuses an incomplete capture (`CAPTURE_INCOMPLETE`) or a different wallet (`WALLET_MISMATCH`), reported as `ARTIFACT_OR_CONFIGURATION_INVALID`. An incomplete capture is inspected without decoding and prints `decoderStatus=CAPTURE_INCOMPLETE`. Capture itself never decodes and prints `decoderStatus=NOT_RUN`.

A successful, SOL-quoted Pump.fun bonding-curve buy signed by the wallet is supported, called top-level or through any router. Two instructions qualify: the legacy `buy` ([decoder decision](adr/20261003-pumpfun-bonding-curve-buy-decoder.md)) and `buy_exact_quote_in_v3` ([V3 decision](adr/20261010-pumpfun-exact-quote-in-v3-buys.md)). The transaction must contain exactly one of them and exactly one Pump.fun `TradeEvent` instruction under the same top-level instruction. The event must be a buy by the wallet, and both amounts must be positive. A legacy `buy`'s token-amount argument must equal the event's. A V3 buy's spendable amount must equal the curve payment plus both fees, and its tokens must reach its minimum. A curve quoted in another token (zero SOL amount and zero virtual SOL reserves) is rejected as `NON_SOL_QUOTE`. The wallet-owned token accounts for the mint must change by exactly that token amount, with consistent decimals. The event is read from the instruction, never from `Program data:` log lines.

A decoded buy becomes one wallet transaction with source `pumpfun-bonding-curve-buy/v1` at confirmed commitment. Its identity is the signature plus the top-level instruction that contains the buy. The base is the event mint with the token-account decimals; the quote is the wrapped SOL mint with 9 decimals, and the quote amount is the curve's `sol_amount`. Pump.fun protocol and creator fees, router fees, tips and network fees are excluded; the simulator applies its own cost model.

Every selected signature gets one decode outcome:

- `DECODED`: the supported buy.
- `NOT_SIGNER`, `FAILED`, `UNAVAILABLE`, `UNSUPPORTED_VERSION`, `WITHHELD_UNSAFE_TEXT`, `WALLET_ABSENT`: the capture outcome passed through; nothing is decoded.
- `UNSUPPORTED_ROUTE`: no Pump.fun buy or trade event, a sell or other unsupported Pump.fun instruction (such as `sell_v3`, `buy_exact_quote_in` or `buy_v3`), a sell event, or a curve quoted in another token.
- `AMBIGUOUS`: more than one buy or event, another event user, an amount, balance or decimals mismatch, a buy and event under different top-level instructions, zero amounts, or malformed instruction or transaction data.

Inspection prints, without raw transaction JSON:

```text
replayReady=false decoderStatus=PUMPFUN_BONDING_CURVE_BUY_V2
decode.DECODED=<n>
decode.<outcome>=<n>   (one line per outcome)
rejection.<reason>=<n>   (for example NO_BUY or NON_SOL_QUOTE)
rejectedInstruction.<name>=<n>   (Pump.fun instructions in rejected transactions, for example SELL_V3; OTHER_PUMPFUN, NO_PUMPFUN)
decodedTrade=<signature> slot=<slot> mint=<mint> baseRaw=<n> baseDecimals=<d> quoteRaw=<n> quoteDecimals=9 instructionIndex=<i>
```

At most three `decodedTrade` lines are printed, in slot, signature and instruction order. `replayReady` stays `false`: a decoded buy alone cannot price the later +1/+2/+3 slots. Those prices come from the [market capture](#market-capture).

## Market capture

This is unit 3 of the plan, as changed by its Amendment B. The wallet's own buy price cannot fill the +1/+2/+3 entries or the exits, so the command records other traders' Pump.fun bonding-curve trades of the same mint at those slots, and inspection turns them into observations.

### Prerequisite

A complete, decodable wallet capture with at least one decoded buy (see above). The command verifies and decodes it before it reads `SOLANA_RPC_URL` or makes any request. An incomplete, tampered or buy-free wallet capture is refused as `ARTIFACT_OR_CONFIGURATION_INVALID`. Configure the terminal as for wallet capture.

Run market capture soon after the wallet capture. Later trades of an active token add listing pages in front of the window; the page bound makes that visible as `COVERAGE_INCOMPLETE` rather than hiding it.

### Capture

Only the user runs this command against a live provider. Like wallet capture, it never runs in build or test, does not start the backend and does not connect to PostgreSQL.

```powershell
.\gradlew.bat captureReplayMarkets -PwalletCaptureDirectory=build/captures/decu-pumpfun-buys -PcaptureDirectory=build/captures/decu-dip-markets -PscanPages=50 -PmaxTransactions=50000 -PrequestBudget=60000 -PbyteBudget=4294967296
```

`-PwalletCaptureDirectory` has no default. `-PcaptureDirectory` defaults to `build/captures/replay-markets` and must differ from the wallet capture directory. The defaults are 20 pages, 300 transactions, 400 requests and 100 MiB, far too small for the dip-entry windows; use values like those shown. Bounds: `scanPages` 1–500 per window, `maxTransactions` 1–50,000 in total, `requestBudget` 1–60,000, `byteBudget` up to 4 GiB.

**Deadline.** Each run of `captureWalletHistory` or `captureReplayMarkets` stops after its operation deadline, 10 minutes unless `-PoperationTimeoutMinutes=<1..30>` is given. A stop prints `stopReason=<category>`: `DEADLINE`, `REQUESTS`, `BYTES`, `RESPONSE_BYTES`, or a provider category. After `stopReason=DEADLINE`, re-run the same command to resume from the saved checkpoint. At the capture's pacing a 10-minute run fetches roughly 1,100–1,200 transactions, so a large market capture takes several runs; `-PoperationTimeoutMinutes=30` triples that per run.

Run it soon after the wallet capture: a window's listing starts at the newest trade, so every trade after the window adds pages before it.

### Windows

The [dip-entry strategy](adr/20261008-dip-entry-strategy.md) starts one cycle per mint, at the wallet's first decoded buy of it, and watches 4,500 slots (about 30 minutes) for the dip, the entry and the exit. So each mint gets one window, [S+1, S+4500], where S is the slot of the wallet's first buy of it; later buys of the mint need no window of their own. The manifest (schema version 2) records the horizon and the maximum observation age it was planned for. A console run with a longer horizon needs a new capture.

For each window, discovery lists the mint's signatures newest-first in pages of 1,000, at confirmed commitment, with `until` set to the source buy so it never lists anything older. Every Pump.fun bonding-curve trade passes the mint account, so this finds that mint's trades. Failed entries and successful entries newer than the window are counted and skipped. Successful entries inside the window are selected. Each window ends with one coverage:

- `COMPLETE`: the listing reached an entry older than the window, or a short page (the source buy or history end).
- `COVERAGE_INCOMPLETE`: `scanPages` pages were scanned without reaching the window's start. Nothing is fetched for it. Its cursor, counts and any in-window signatures already selected are kept, so it can be extended (below).
- `OVER_CAP`: the window would take the total of fetched transactions above `maxTransactions`. Nothing is fetched for it; the selection is not truncated.

Selected transactions are fetched once each, even when two windows share one, with the same structure, identity, credential-echo and unsafe-text checks, hashed atomic artifacts, lock, retries, budgets and resume behaviour as wallet capture. No account has to appear in a market transaction. A rerun with the same directory continues where it stopped; it must use the same wallet capture (the same windows) and `maxTransactions`, and a `scanPages` no smaller than the saved one, otherwise it is refused with `RESUME_IDENTITY`. The manifest is `market-manifest.json`, schema version 2; version 1 captures (12-slot windows) are refused. Detail fetching checkpoints the manifest every 100 transactions, and on a provider or budget stop. If the process is killed between checkpoints, the next run refetches up to 99 transactions written since, replacing their unreferenced files; their requests are then counted again, so allow a little budget headroom.

**Extending a scan.** A rerun with a larger `scanPages` raises the saved bound and reopens every `COVERAGE_INCOMPLETE` window. Each continues before its saved cursor; pages already scanned are never listed again, and its counts carry on. `requestBudget` and `byteBudget` apply to each invocation. A resume receives a fresh allowance; the manifest records cumulative usage separately. Raise the per-invocation allowance only if you want more work during that explicit run. For example, after a 50-page run that stopped short:

```powershell
.\gradlew.bat captureReplayMarkets -PwalletCaptureDirectory=build/captures/decu-pumpfun-buys -PcaptureDirectory=build/captures/decu-dip-markets -PscanPages=500 -PmaxTransactions=50000 -PrequestBudget=60000 -PbyteBudget=4294967296
```

### Cost

A window costs at most `scanPages` listing pages (about 230 KB each on a busy mint), plus one detail request per selected transaction, plus one genesis request per run. Detail requests dominate. In the user's 2026-10-08 capture, Decu's tokens traded about 1–5 times per slot near his buys, so a 4,500-slot window can hold roughly 2,000–15,000 trades. That means as many requests, about 37 KB of artifact each (75–550 MB per token), and 15 minutes to 2 hours per token at the capture's pacing. Capture one token's wallet sample first (`-Plimit=1`) to measure. Requests are not a billing-credit estimate.

### Inspect offline

```powershell
.\gradlew.bat inspectReplayMarkets -PcaptureDirectory=build/captures/replay-markets
```

Inspection needs neither credentials nor PostgreSQL. It verifies the manifest and every artifact, then, once acquisition has finished, extracts observations as the [observation decision](adr/20261004-pumpfun-last-trade-observations.md) specifies: one `LAST_TRADE` observation per valid Pump.fun `TradeEvent` instruction, buy or sell, by any user, priced from the curve amounts with all fees excluded, at scale 38. A top-level event, which has no inner instruction, gets `-` as its inner index in the observation id. Both commands print, without raw transaction JSON:

```text
windows=<n> scanPages=<n> maxTransactions=<n> acquisitionComplete=<bool> complete=<bool>
window=<mint> fromSlot=<a> toSlot=<b> coverage=<coverage> scanned=<n> skippedFailed=<n> skippedNewer=<n> selected=<n>
requestsUsed=<n> bytesUsed=<n>
transaction.<outcome>=<n>
replayReady=false extractorStatus=<NOT_RUN | CAPTURE_INCOMPLETE | PUMPFUN_BONDING_CURVE_TRADE_V1>
```

Inspection then adds `observations=<n>`, a `windowYield=` line per window, `exclusion.<reason>=<n>` for every reason and up to three `observation=<id> slot=<slot> price=<price>` lines in slot and id order.

Observations come only from windows whose coverage is `COMPLETE` and whose every selected transaction was captured. A selected transaction that is unavailable, of an unsupported version or withheld for unsafe text could hide an earlier trade, so its window yields no observations and reports `detailGaps`. Events outside every such window count as `OUT_OF_WINDOW`. `EXCEEDS_REPLAY_BOUNDS` counts an event whose price rounds to zero or exceeds the replay format's `numeric(78,38)`, or whose block time or id would not fit it; nothing is truncated.

Each observation also records whether its trade completed the bonding curve (the event's real token reserves reached 0): from that trade on, the token has migrated and the strategy uses no later price.

Inspection always prints `replayReady=false`, because it does not write a dataset; `exportRecordedReplay` does (below).

## SOL/USDT rates

The dip-entry strategy's market-cap rule needs a SOL/USDT rate, and a replay dataset carries one for the minute of every trade ([decision](adr/20261008-reference-rates-in-dataset.md)). Only the user runs this; it calls Binance's public API (no key):

```powershell
.\gradlew.bat captureReferenceRates -PmarketCaptureDirectory=build/captures/decu-dip-markets -PcaptureDirectory=build/captures/decu-dip-rates
```

```powershell
.\gradlew.bat inspectReferenceRates -PcaptureDirectory=build/captures/decu-dip-rates
```

The capture verifies the market capture, collects the UTC minute of every in-window trade, and records the SOLUSDT 1-minute kline open for each minute in `rates-manifest.json` (hash-enveloped, with lock and atomic writes). It uses about one request per token. A minute Binance does not answer (`RATE_LIMITED`, `REGION_BLOCKED`, `TIMEOUT`, ...) is recorded with that reason, and the command reports the capture incomplete; a rerun asks only for the missing minutes. Both commands print `minutes=<n> withRate=<n> complete=<bool>`, a count per unavailable reason, and the first and last minute's rate.

## Export to a replay dataset

```powershell
.\gradlew.bat exportRecordedReplay -PwalletCaptureDirectory=build/captures/decu-pumpfun-buys -PmarketCaptureDirectory=build/captures/decu-dip-markets -PratesCaptureDirectory=build/captures/decu-dip-rates "-Poutput=build/captures/decu-dip-v3.json"
```

In PowerShell, quote any `-P` value that contains a `.`: unquoted, PowerShell splits it at the dot and Gradle reports `Task '.json' not found`.

Export is offline: it needs neither credentials nor PostgreSQL. `-PwalletCaptureDirectory` has no default; the others default to `build/captures/replay-markets`, `build/captures/reference-rates` and `build/captures/recorded-replay-v3.json`. The command:

1. verifies the wallet capture is complete, decodes it and plans the market windows of the buys the strategy accepts, exactly as `captureReplayMarkets` does;
2. verifies the market capture and extracts its observations, as `inspectReplayMarkets` does;
3. refuses a market capture whose windows or window parameters differ from that plan (`MARKET_CAPTURE_MISMATCH`), or that is not complete or has a window that yields no observations (`MARKET_CAPTURE_INCOMPLETE`), so a gap is never exported as an absence of trades. A market capture whose acquisition has not finished is refused earlier, by its own decode, with `CAPTURE_INCOMPLETE`. A trade inside a window that could not become an observation (any exclusion except `OUT_OF_WINDOW` and `QUOTE_MINT`) refuses the export with `MARKET_CAPTURE_EXCLUSIONS`, because it could change which observation a fill uses;
4. reads the verified rates and refuses `REFERENCE_RATES_INCOMPLETE` unless every trade's minute has one;
5. builds one v3 dataset: the tracked wallet, its decoded transactions, every observation with its curve state, and the rate of every observation minute. More than 100,000 transactions or observations is refused (`DATASET_BOUNDS`), never truncated;
6. writes the canonical JSON with `ReplayDatasetWriter`, loads it back with the replay loader and its checksum check, then writes it to a temporary file and renames it into place. An existing file with identical content is kept (`written=false`); a different one, even one created during the export, is refused (`OUTPUT_EXISTS`) and left untouched.

It prints the output path, the dataset checksum, the tracked wallet, the counts and `replayReady=true schemaVersion=3`, never the dataset JSON. A refused export prints `operation=exportRecordedReplay category=<category>` and `reason=<code>`. The same captures always produce the same bytes and checksum.

### Run it

- **Offline preview:** `.\gradlew.bat replay "-Pdataset=build/captures/decu-dip-v3.json" "-Preport=build/captures/decu-dip-report.json"` prints each cycle's state and P&L and writes the full report.
- **Console:** on the Datasets screen, import the file. It is registered by its checksum, so importing it again under any name returns the same dataset. Start a run with the defaults, or change the dip, take profit, stop loss, minimum market cap or a shorter horizon. A horizon longer than the captured one needs a new market capture.

What to inspect in the run report: each cycle's intent (P0, dip level, watch outcome and the dip trigger with its market cap and rate), its fill (`observationId` names the trade's signature and instruction indexes; `executionPrice` includes the modelled slippage), its position's take-profit and stop-loss levels and exit trigger, and its close (slot, observation, reason, realized P&L) or why it stayed open. Starting the same run again returns the existing run with no new rows.

### Result from the user's capture (2026-10-04), under the replaced copy strategy

This dataset was schema v1 and no longer loads; it is kept as a record. Decu's buy at slot 453004996 and 17 same-mint trades in slots 453004997–453005007 export to dataset `3a4771170535f8c6b8d9828e01c61bee876938105eb65f532338285129d12e2d`. With the defaults (notional 100 in the quote unit, wrapped SOL here; 10 bps fee; 50 bps slippage) all three entries fill at their target slot and close after five slots:

| Entry | Fill slot | Close slot | Realized P&L |
|---|---|---|---|
| +1 | 453004997 | 453005002 | −0.469035946332 |
| +2 | 453004998 | 453005003 | −0.689058352601 |
| +3 | 453004999 | 453005005 (no trade at 453005004) | −2.422146778200 |

Total −3.580241077133. These are modelled paper results on observed curve prices, not Decu's actual profit and not executable quotes. One source buy is a single example, not a sample.

### Limitations

- Only Pump.fun bonding-curve trades. After a token migrates to PumpSwap there are no observations; the strategy records `MIGRATED_BEFORE_ENTRY` or leaves the position open as `MIGRATED`.
- Observations are observed trade prices, not executable quotes. Depth is unknown, so liquidity is absent; fills modelled from them carry the simulator's assumed fees and slippage, not real price impact.
- The listing covers the mint's address references only, within the page bound.

## Calibrate execution offline

```powershell
.\gradlew.bat calibrateExecution -PwalletCaptureDirectory=build/captures/decu-pumpfun-buys -PmarketCaptureDirectory=build/captures/decu-dip-markets "-Preport=build/captures/execution-calibration.json"
```

This command reads and verifies only local captures. It needs neither RPC credentials, reference rates nor PostgreSQL. It uses the same completeness, market-window matching and exclusion checks as export. `walletCaptureDirectory` is required; `marketCaptureDirectory` defaults to `build/captures/replay-markets` and `report` to `build/captures/execution-calibration.json`. It creates the report directory and replaces an existing report.

Every decoded buy contributes three checks:

- **Math:** subtract its SOL delta and restore its token delta to reconstruct the pre-trade curve. The curve formula must reproduce the recorded token amount and both ceiling-rounded fees exactly. The JSON lists each mismatching signature with its actual and computed amounts; invalid reconstructed reserves produce a mismatch with no computed token amount.
- **Latency:** predict tokens using the newest same-mint observation in a strictly earlier slot. If several observations share that slot, choose the last in canonical `(slot, id)` order. Per-buy error is signed `(actual - predicted) / predicted * 10000`, rounded to scale 2 HALF_EVEN. The first buy of each mint is normally `NO_PRIOR_STATE`, because market windows start at its slot plus one. A prior curve that cannot supply the buy or yields zero raw tokens is `UNPREDICTABLE`, with its observation and reason recorded.
- **Costs:** report `meta.fee` and the signed residual `-walletLamportDelta - solAmount - feeLamports - creatorFeeLamports - transactionFeeLamports`. This includes tips, token-account rent and refunds; negative residuals are preserved.

The command prints `buys= mathExact= mathMismatches= latencyChecked= noPriorState= medianErrorBps= maxErrorBps= medianTxFeeLamports= medianOtherLamports=`. The error median and maximum use absolute per-buy errors. Medians use sorted exact decimals, with the middle value for odd counts or the mean of the middle two for even counts; errors round HALF_EVEN to two decimal places and lamport medians preserve exact half-lamport values. Missing summary values print `none` and are JSON nulls. The JSON also gives the unpredictable count and every per-buy result, in `(slot, signature)` order. The same captures produce identical report bytes.

Inspect the fee and residual medians before choosing `transactionCostSol`, and the latency errors before choosing `latencySlots`. The residual combines rent and tips, so it is not a direct fee estimate. The current model does not charge account rent because closing the account refunds it; changing defaults based on this report is a separate follow-up.
## Evidence needed for the next stage

Supporting another route needs new evidence. Share the sanitized inspection summary and a sanitized transaction artifact for one example of that route. Failed transactions are no longer captured. Remove unrelated personal data or secret-bearing text; never include the environment file, endpoint, request headers or API key. Public on-chain wallet addresses and signatures are allowed. The agent will inspect only samples you explicitly provide, with no provider requests.

More than one source buy: the wallet's own trades are rare among its address references (1 in 6,911 in the user's first capture, 0 in 5,449 in the second), so use the [Pump.fun user-buy scope](#scope-the-wallets-own-pumpfun-buys). Export still refuses the whole dataset if any buy's window is unusable; whether to drop such a buy instead is a separate decision.

Keep raw evidence and its manifest together. `build/captures/` is ignored local output and is removed by a clean build-directory deletion. Export evidence you need to retain before running `clean`; never commit captures or credentials.
