# Real wallet history capture

This is the first stage of the [approved real replay plan](plans/20261003-real-wallet-replay.md), as changed by its Amendment A ([successful-reference discovery](adr/20261003-successful-reference-discovery.md)). It records a bounded mainnet sample of successful transactions, verifies its evidence offline and decodes one verified route, the Pump.fun bonding-curve buy (see [Decoding](#decoding)). A second command captures the same-mint market trades around each decoded buy (see [Market capture](#market-capture)). A third, offline command exports both into a replay dataset the console can import and run (see [Export](#export-to-a-replay-dataset)).

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

The selection, the counts of scanned and skipped-failed entries, and the last *scanned* entry (the scan cursor) are saved after every listing page, including a page that held only failed entries. Discovery stores only each selected signature and its slot, the cursor's signature and slot, and the two counts; signature memo text is never saved. If the provider fails or a budget runs out mid-scan, running the command again with the same directory continues before the saved cursor. It never rescans saved pages or selects newer transactions. The wallet, `limit` and `scanPages` must match the saved capture. Use a new directory for a new research sample.

The manifest format is version 2. Directories captured before successful-reference discovery (version 1) are refused for both resume and inspection with category `MANIFEST_VERSION`, reported as `ARTIFACT_OR_CONFIGURATION_INVALID`. Capture into a new directory, for example `build/captures/decu-mainnet-successful`. Do not manually edit artifacts or the manifest. If a run is interrupted after writing a transaction file but before saving the manifest, the next run fetches that signature again and replaces the unreferenced file.

Only one capture can write to a directory at a time. The command holds an exclusive lock on `.capture.lock` inside the capture directory. A second capture into the same directory fails before any provider request, reported as `ARTIFACT_OR_CONFIGURATION_INVALID`. The lock file can remain after the command ends; it contains no data and inspection ignores it.

Legacy, version 0 and version 1 transactions are captured: details are requested with `maxSupportedTransactionVersion` 1, the same value the console probe uses. Mainnet carries version 1 transactions; a capture limited to version 0 recorded all 100 of this wallet's latest references as unsupported. The provider adds `transactionIndex` to the result and, for version 1, `transactionConfig` (priority fee, compute unit limit, loaded accounts data size limit, heap size) to the message. Both are type-checked like the other fields. Later versions are recorded as an explicit `UNSUPPORTED_VERSION` gap with no artifact.

`UNSUPPORTED_VERSION` is a resolved outcome, so a resumed capture does not fetch those transactions again. After a change to supported versions, capture into a new directory.

If any provider response repeats part of the configured endpoint, the whole response is discarded as a non-retryable provider failure and nothing from it is written. This covers the full URL and any path segment or query value of at least 8 characters, in raw or decoded form, compared case-insensitively. The capture then stops with incomplete status, and a rerun stops at the same point. Report the printed category and signature, never the payload.

## Inspect offline

```powershell
.\gradlew.bat inspectWalletHistory -PcaptureDirectory=build/captures/decu-mainnet-successful
```

Inspection needs neither RPC credentials nor PostgreSQL. It verifies artifact paths, hashes and selection consistency, including the discovery counts, cursor and stop reason, before presenting the audit. Both commands print `scope=LATEST_SUCCESSFUL_WALLET_REFERENCES` and a line with `scanned=`, `skippedFailed=`, `scanPages=` (the page bound) and `discoveryStop=` (`NONE` while discovery is incomplete). Valid missing/null transaction responses are recorded as unavailable; provider interruptions leave incomplete acquisition status.

Every selected transaction was successful in the listing. If its fetched detail nevertheless reports an error, whether a named error string such as `AccountInUse` or a structured instruction error, the detail contradicts discovery. It is recorded as `INVALID_TRANSACTION`, nothing is stored and acquisition stays incomplete. The `FAILED` count is therefore expected to be 0. Capture records successful signer transactions as `UNCLASSIFIED`; inspection then decodes them (see [Decoding](#decoding)). The audit does not invent pairs, token amounts or successful trades.

Every transaction is checked against the RPC's `jsonParsed` field types: slot, block time, version, signatures, account keys, instructions, recent blockhash, error, fee, balances and, when present, token balances, inner instructions, log messages and the other metadata fields. A response with the wrong slot or signature, without the wallet among its account keys, or with any field of the wrong type is recorded as `INVALID_TRANSACTION`. Nothing is stored for it and acquisition stays incomplete.

Some transactions carry public on-chain text, such as memos, log messages or parsed instruction fields, that contains a link or a credential-like keyword (`api-key`, `authorization`, `bearer`). These are recorded as `WITHHELD_UNSAFE_TEXT`. The audit keeps their signature and slot, but the raw transaction is not stored. This is a resolved, explicit gap: retrying cannot change it, and capture continues to the next signature.

The sample covers successful transactions referencing this wallet, not all historical current/closed associated token accounts. It is up to 100 successful transactions within the scanned range, not necessarily 100 trades or 100 transactions the wallet signed. A completed acquisition can still have explicit unavailable, unsupported-version or withheld-text data gaps. Invalid or malformed data keeps acquisition incomplete.

## Decoding

When the capture is complete, `inspectWalletHistory` also decodes it offline. It first repeats the full verification above, then decodes the verified artifacts. Decoding refuses an incomplete capture (`CAPTURE_INCOMPLETE`) or a different wallet (`WALLET_MISMATCH`), reported as `ARTIFACT_OR_CONFIGURATION_INVALID`. An incomplete capture is inspected without decoding and prints `decoderStatus=CAPTURE_INCOMPLETE`. Capture itself never decodes and prints `decoderStatus=NOT_RUN`.

One route is supported, as decided in the [decoder decision](adr/20261003-pumpfun-bonding-curve-buy-decoder.md): a successful Pump.fun bonding-curve `buy` signed by the wallet, called top-level or through any router. The transaction must contain exactly one Pump.fun `buy` and exactly one Pump.fun `TradeEvent` instruction under the same top-level instruction. The event must be a buy by the wallet, its token amount must equal the buy's argument, and both amounts must be positive. The wallet-owned token accounts for the mint must change by exactly that token amount, with consistent decimals. The event is read from the instruction, never from `Program data:` log lines.

A decoded buy becomes one wallet transaction with source `pumpfun-bonding-curve-buy/v1` at confirmed commitment. Its identity is the signature plus the top-level instruction that contains the buy. The base is the event mint with the token-account decimals; the quote is the wrapped SOL mint with 9 decimals, and the quote amount is the curve's `sol_amount`. Pump.fun protocol and creator fees, router fees, tips and network fees are excluded; the simulator applies its own cost model.

Every selected signature gets one decode outcome:

- `DECODED`: the supported buy.
- `NOT_SIGNER`, `FAILED`, `UNAVAILABLE`, `UNSUPPORTED_VERSION`, `WITHHELD_UNSAFE_TEXT`: the capture outcome passed through; nothing is decoded.
- `UNSUPPORTED_ROUTE`: no Pump.fun buy or trade event, a sell or other Pump.fun instruction such as `buy_exact_sol_in`, or a sell event.
- `AMBIGUOUS`: more than one buy or event, another event user, an amount, balance or decimals mismatch, a buy and event under different top-level instructions, zero amounts, or malformed instruction or transaction data.

Inspection prints, without raw transaction JSON:

```text
replayReady=false decoderStatus=PUMPFUN_BONDING_CURVE_BUY_V1
decode.DECODED=<n>
decode.<outcome>=<n>   (one line per outcome)
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
.\gradlew.bat captureReplayMarkets -PwalletCaptureDirectory=build/captures/decu-mainnet-successful -PcaptureDirectory=build/captures/replay-markets -PscanPages=20 -PmaxTransactions=300 -PrequestBudget=400 -PbyteBudget=104857600
```

`-PwalletCaptureDirectory` has no default. `-PcaptureDirectory` defaults to `build/captures/replay-markets` and must differ from the wallet capture directory. The other values shown are the defaults. Bounds: `scanPages` 1–500 per window, `maxTransactions` 1–1,000 in total, `requestBudget` 1–1,000, `byteBudget` up to 500 MiB.

### Windows

Each decoded buy at slot S that the strategy accepts gets the window [S+1, S+12], inclusive. The entries target S+1 to S+3 and may fill up to two slots later; an exit waits five slots after its fill and may also fill up to two slots later, so S+3+2+5+2 = S+12. The window is derived from the accepted defaults (entry offsets 1–3, wait 2, age 2, holding 5), which the manifest records. A console run with a longer wait or holding needs a new capture. Windows of the same mint that overlap or touch are merged; a merged window is bounded by its oldest source buy.

For each window, discovery lists the mint's signatures newest-first in pages of 1,000, at confirmed commitment, with `until` set to the source buy so it never lists anything older. Every Pump.fun bonding-curve trade passes the mint account, so this finds that mint's trades. Failed entries and successful entries newer than the window are counted and skipped. Successful entries inside the window are selected. Each window ends with one coverage:

- `COMPLETE`: the listing reached an entry older than the window, or a short page (the source buy or history end).
- `COVERAGE_INCOMPLETE`: `scanPages` pages were scanned without reaching the window's start. Nothing is fetched for it. Its cursor, counts and any in-window signatures already selected are kept, so it can be extended (below).
- `OVER_CAP`: the window would take the total of fetched transactions above `maxTransactions`. Nothing is fetched for it; the selection is not truncated.

Selected transactions are fetched once each, even when two windows share one, with the same structure, identity, credential-echo and unsafe-text checks, hashed atomic artifacts, lock, retries, budgets and resume behaviour as wallet capture. No account has to appear in a market transaction. A rerun with the same directory continues where it stopped; it must use the same wallet capture (the same windows) and `maxTransactions`, and a `scanPages` no smaller than the saved one, otherwise it is refused with `RESUME_IDENTITY`. The manifest is `market-manifest.json`, schema version 1.

**Extending a scan.** A rerun with a larger `scanPages` raises the saved bound and reopens every `COVERAGE_INCOMPLETE` window. Each continues before its saved cursor; pages already scanned are never listed again, and its counts carry on. `requestBudget` and `byteBudget` are totals across all runs into the directory, so raise them too. For example, after a 50-page run that stopped short:

```powershell
.\gradlew.bat captureReplayMarkets -PwalletCaptureDirectory=build/captures/decu-mainnet-successful -PcaptureDirectory=build/captures/replay-markets -PscanPages=500 -PmaxTransactions=300 -PrequestBudget=1000 -PbyteBudget=314572800
```

### Cost

A window costs at most `scanPages` listing pages (about 230 KB each on a busy mint), plus one detail request per selected transaction, plus one genesis request per run. For the single decoded buy, a default run costs at most 1 + 20 + 300 = 321 requests, within the default 400. Requests are not a billing-credit estimate.

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

Inspection always prints `replayReady=false`, because it does not write a dataset; `exportRecordedReplay` does (below).

## Export to a replay dataset

```powershell
.\gradlew.bat exportRecordedReplay -PwalletCaptureDirectory=build/captures/decu-mainnet-successful -PmarketCaptureDirectory=build/captures/replay-markets -Poutput=build/captures/recorded-replay-v1.json
```

Export is offline: it needs neither credentials nor PostgreSQL. `-PwalletCaptureDirectory` has no default; the other two values shown are the defaults. The command:

1. verifies the wallet capture is complete, decodes it and plans the market windows of the buys the strategy accepts, exactly as `captureReplayMarkets` does;
2. verifies the market capture and extracts its observations, as `inspectReplayMarkets` does;
3. refuses a market capture whose windows or window parameters differ from that plan (`MARKET_CAPTURE_MISMATCH`), or that is not complete or has a window that yields no observations (`MARKET_CAPTURE_INCOMPLETE`), so a gap is never exported as an absence of trades. A market capture whose acquisition has not finished is refused earlier, by its own decode, with `CAPTURE_INCOMPLETE`. A trade inside a window that could not become an observation (any exclusion except `OUT_OF_WINDOW` and `QUOTE_MINT`) refuses the export with `MARKET_CAPTURE_EXCLUSIONS`, because it could change which observation a fill uses;
4. builds one v1 dataset: the tracked wallet, its decoded transactions and every observation. More than 10,000 of either is refused (`DATASET_BOUNDS`), never truncated;
5. writes the canonical JSON with `ReplayDatasetWriter`, loads it back with the replay loader and its checksum check, then writes it to a temporary file and renames it into place. An existing file with identical content is kept (`written=false`); a different one, even one created during the export, is refused (`OUTPUT_EXISTS`) and left untouched.

It prints the output path, the dataset checksum, the tracked wallet, the counts and `replayReady=true schemaVersion=1`, never the dataset JSON. A refused export prints `operation=exportRecordedReplay category=<category>` and `reason=<code>`. The same captures always produce the same bytes and checksum.

### Run it

- **Offline preview:** `.\gradlew.bat replay -Pdataset=build/captures/recorded-replay-v1.json -Preport=build/captures/recorded-replay-report.json` prints each entry offset's state and P&L and writes the full report.
- **Console:** on the Datasets screen, import the file. It is registered by its checksum, so importing it again under any name returns the same dataset. Start a run with the default settings. The window was captured for those defaults (entry offsets 1–3, wait 2, holding 5); a longer wait or holding needs a new market capture.

What to inspect in the run report: each scenario's intent (+1/+2/+3 target slot), its fill (`observationId` names the trade's signature and instruction indexes; `executionPrice` includes the modelled slippage), and its close (slot, observation, reason, realized P&L). Starting the same run again returns the existing run with no new rows.

### Result from the user's capture (2026-10-04)

Decu's buy at slot 453004996 and 17 same-mint trades in slots 453004997–453005007 export to dataset `3a4771170535f8c6b8d9828e01c61bee876938105eb65f532338285129d12e2d`. With the defaults (notional 100 in the quote unit, wrapped SOL here; 10 bps fee; 50 bps slippage) all three entries fill at their target slot and close after five slots:

| Entry | Fill slot | Close slot | Realized P&L |
|---|---|---|---|
| +1 | 453004997 | 453005002 | −0.469035946332 |
| +2 | 453004998 | 453005003 | −0.689058352601 |
| +3 | 453004999 | 453005005 (no trade at 453005004) | −2.422146778200 |

Total −3.580241077133. These are modelled paper results on observed curve prices, not Decu's actual profit and not executable quotes. One source buy is a single example, not a sample.

### Limitations

- Only Pump.fun bonding-curve trades. A token that graduated to PumpSwap, or any other route, yields no observations; the simulator then records missing-observation rejections or open positions.
- Observations are observed trade prices, not executable quotes. Depth is unknown, so liquidity is absent; fills modelled from them carry the simulator's assumed fees and slippage, not real price impact.
- The listing covers the mint's address references only, within the page bound.

## Evidence needed for the next stage

Supporting another route needs new evidence. Share the sanitized inspection summary and a sanitized transaction artifact for one example of that route. Failed transactions are no longer captured. Remove unrelated personal data or secret-bearing text; never include the environment file, endpoint, request headers or API key. Public on-chain wallet addresses and signatures are allowed. The agent will inspect only samples you explicitly provide, with no provider requests.

More than one source buy needs a different history scope. The wallet's own trades are rare among its address references (1 in 6,911 in the user's capture), so the latest references hold few buys. Choosing that scope, for example the wallet's token accounts or a provider-filtered history, is a separate decision.

Keep raw evidence and its manifest together. `build/captures/` is ignored local output and is removed by a clean build-directory deletion. Export evidence you need to retain before running `clean`; never commit captures or credentials.
