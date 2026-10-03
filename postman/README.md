# Postman API checks

Import both the collection and its environment from this directory into Postman. The application and Solana collections are independent. Console requests persist local replay data. Solana requests are strictly read-only.

## Test the local application

1. From the repository root, start PostgreSQL with `docker compose up -d` and the application with `.\gradlew.bat bootRun` (or run it from IntelliJ).
2. Import `trenching-bot.postman_collection.json` and `local.postman_environment.json`.
3. Select **Trenching Bot — Local**. The default `baseUrl` is `http://localhost:8081`; update it if `SERVER_PORT` differs. Use a URL without a trailing slash. Local PostgreSQL uses port 5433.
4. Open **Trenching Bot — Application API**, then **Run collection**. The six Actuator requests preserve their health assertions. The console folder imports a dataset and starts a persisted replay; optional Solana probes are skipped unless `allowSolanaProbe=true`. Assertions appear in the test results.

| Request | Verifies |
| --- | --- |
| GET /actuator | Exposed health/info links |
| GET /actuator/health | Overall UP and local PostgreSQL component UP |
| GET /actuator/health/liveness | Process liveness |
| GET /actuator/health/readiness | Spring readiness state |
| GET /actuator/info | Valid JSON object; `{}` is expected today |
| GET /actuator/health/db | Local database UP and PostgreSQL product name |

Actuator requests also check HTTP 200, JSON media type and object response. Actuator's vendor JSON media type is accepted. An unhealthy app/database should fail assertions; do not change expected UP to conceal a failure. Liveness/readiness alone do not verify database connectivity.

### Console API

Run **Console API** in order with `trenching.console.enabled=true` (the local-profile default). The defaults request saves the configuration, sample import/upload saves `datasetId` and the chart pair, and run start saves `runId` for the detail/table/report requests. Notional is sent as a JSON string. The bundled sample realizes exactly `26.089701492540`; repeat run verifies idempotency.

The folder covers dataset upload/import/list/detail/observations, config defaults, run start/list/detail/events/observations/orders/positions/report, the dashboard, and passive Solana status. It also checks the per-field 400 envelope using an invalid `feeBps`. Creation accepts 201 or 200 according to `created`; table endpoints assert arrays; report checks its attachment header. For Postman desktop, choose `src/main/resources/replay/samples/weekly-buy-v1.json` for the upload and allow access to that file. Newman resolves this path from the repository root. Files must be UTF-8 JSON and fit the 20 MB upload/request limit.

**Optional Solana probes** contains `POST /api/solana/check` and `/api/solana/address-check`. Its pre-request script skips both requests by default, including whole-collection runs. Explicitly set `allowSolanaProbe=true` to run them and set `address` to a public address to inspect. These can call the backend's configured provider; leave `SOLANA_RPC_URL` in the host environment and never export it. Passive `/api/solana/status` makes no provider call. An unconfigured address check returns the expected 409 envelope.

For a production target, duplicate the environment, change `baseUrl`, set `expectHealthDetails` to `false`, and run only the **Application endpoints** folder. The production profile hides health details, so the **Local only — PostgreSQL details** folder is not applicable. Production execution has not been verified.

## Optional public Solana devnet checks

Import `solana-devnet.postman_collection.json` and `solana-devnet.postman_environment.json`. Select **Trenching Bot — Solana Devnet**, then run **Trenching Bot — Solana Devnet RPC**. Its `rpcUrl` defaults to `https://api.devnet.solana.com`. No registration, credentials, wallet or billing setup is needed for these public requests.

### MVP folder: what the strategy needs

The **MVP** folder holds only the HTTP methods the live wallet adapter needs, per the plan's [required capabilities](../docs/WEEKLY_EXECUTION_PLAN.md#required-capabilities-for-the-mvp). It runs on its own.

| Request | Why |
| --- | --- |
| getVersion, getHealth | Connectivity and node health |
| getSlot | Current confirmed slot: lag tracking and deciding when slot +1/+2/+3 has passed |
| getSignaturesForAddress | Gap recovery and restart backfill from a saved cursor (`before`/`until`) |
| getTransaction | Wallet normalization: slot, block time, success, signer flags and pre/post token balances (mint, owner, raw amount, decimals) |
| getAccountInfo | Fallback for a mint's decimals and token program; `getMultipleAccounts` does the same in one call for several mints |

Set `trackedAddress` to the tracked wallet. It defaults to devnet USDC so the folder runs out of the box; a pool address works too, for price backfill. To run only this folder, open its **⋯** menu and choose **Run folder**, or pass `--folder MVP` to Newman.

The live path also needs two WebSocket subscriptions, which a collection file cannot hold. [`logsSubscribe`](https://solana.com/docs/rpc/websocket/logssubscribe) with `mentions: [wallet]` detects wallet activity, and the same with a pool address can capture the price stream. [`slotSubscribe`](https://solana.com/docs/rpc/websocket/slotsubscribe) gives continuous slot progress. A slot-tagged price source is still undecided and blocks live mode.

### Full reference

The other folders cover every read-only Solana HTTP JSON-RPC method: 50 of the 52 in the [RPC reference](https://solana.com/docs/rpc/http), grouped like the docs. Most are not needed for the MVP.

| Folder | Methods |
| --- | --- |
| Cluster | getVersion, getSlot, getHealth, getGenesisHash, getIdentity, getEpochInfo, getEpochSchedule, getSlotLeader, getSlotLeaders, getLeaderSchedule, getClusterNodes, getVoteAccounts, getHighestSnapshotSlot, getMaxRetransmitSlot, getMaxShredInsertSlot |
| Blocks | getBlocksWithLimit, getBlocks, getBlock, getBlockTime, getBlockCommitment, getBlockHeight, getBlockProduction, getFirstAvailableBlock, minimumLedgerSlot, getRecentPerformanceSamples |
| Transactions | getSignaturesForAddress, getTransaction, getLatestBlockhash, isBlockhashValid, getFeeForMessage, simulateTransaction, getSignatureStatuses, getRecentPrioritizationFees, getTransactionCount |
| Tokens | getTokenSupply, getTokenLargestAccounts, getTokenAccountBalance, getTokenAccountsByOwner, getTokenAccountsByDelegate |
| Accounts | getAccountInfo, getBalance, getMultipleAccounts, getProgramAccounts, getLargestAccounts, getMinimumBalanceForRentExemption |
| Economics | getSupply, getInflationGovernor, getInflationRate, getInflationReward, getStakeMinimumDelegation |

`sendTransaction` and `requestAirdrop` are excluded on purpose because they change chain state. `simulateTransaction` only simulates an unsigned, instruction-free transaction with `sigVerify: false`. WebSocket subscriptions are not included: Postman collection v2.1 files cannot hold WebSocket requests.

**Run the whole collection in order.** Earlier requests save live inputs as collection variables for later ones. These are the confirmed slot, a produced block slot and signature, a recent successful transaction of `tokenMint` (default: Circle's devnet USDC) with a holder account and owner, a vote account and a fresh blockhash. The blockhash is used to build an unsigned message for `getFeeForMessage` and `simulateTransaction`. A request run alone may lack its inputs.

Every response is checked for HTTP 200, JSON-RPC 2.0, the request id and no JSON-RPC error, plus method-specific shape checks. JSON-RPC errors fail even when HTTP status is 200. `getGenesisHash` asserts the devnet genesis hash, so pointing `rpcUrl` at another cluster fails visibly.

The public endpoint answers `getTokenLargestAccounts`, `getLargestAccounts` and `getTokenAccountsByDelegate` with 429 on every call. Against it, those three report **Blocked on public devnet (429)** instead of failing; any other `rpcUrl` asserts them fully. Other public rate limits can still cause occasional failures; rerun later.

Devnet already returns **version 1** transactions, which `maxSupportedTransactionVersion: 0` rejects. `getBlock` and `getTransaction` therefore request version 1, and the live wallet adapter will need the same. Do not store private provider URLs/API keys in exported environments or Git; use Postman Vault or private local values if switching providers.

## Run from PowerShell

With Node.js/npm installed, from the repository root:
```powershell
npm.cmd exec --yes --package=newman@6.2.2 -- newman run postman/trenching-bot.postman_collection.json -e postman/local.postman_environment.json --timeout-request 15000
npm.cmd exec --yes --package=newman@6.2.2 -- newman run postman/solana-devnet.postman_collection.json -e postman/solana-devnet.postman_environment.json --timeout-request 30000 --delay-request 300
npm.cmd exec --yes --package=newman@6.2.2 -- newman run postman/solana-devnet.postman_collection.json -e postman/solana-devnet.postman_environment.json --folder MVP --timeout-request 30000 --delay-request 300
```
The third command runs only the MVP folder. The application collection is safe from provider calls with the default `allowSolanaProbe=false`; it does persist local replay data. To run just the console checks, add `--folder "Console API"` to its command. The two devnet commands are optional live provider calls and must be run explicitly by the user.
Newman is downloaded to npm's cache; it is not a project dependency. A nonzero exit status indicates a failed request or assertion.

## Coverage and limits

Application checks exercise Actuator health and the gated console API against the running app and its PostgreSQL connection. The console creates immutable replay datasets/runs; it does not submit trades. Solana checks call providers only when explicitly enabled and do not validate unfinished live wallet/market integrations. No signed or submitted transactions, airdrops or real trading requests are included.

## Verified on 2026-10-02

Executed with Newman 6.2.2 against the running local application and the real public Solana devnet endpoint:

| Collection | Requests passed | Assertions passed | Failures |
| --- | --- | --- | --- |
| Application API, local profile with PostgreSQL | 6/6 | 25/25 | 0 |
| Solana Devnet RPC, whole collection (MVP + full reference) | 56/56 | 332/332 | 0 |
| Solana Devnet RPC, MVP folder alone | 6/6 | 45/45 | 0 |

Three of the whole-collection assertions are the expected public-endpoint 429 reports. Devnet returned Solana core `4.4.0-beta.0`; this is an observation from that run, not a pinned assertion. Both collections also passed the official Postman v2.1 JSON schema validation. Environment files parsed successfully and were used in the live runs.

Newman JSON reports are in ignored `build/postman/application-report.json` and `build/postman/solana-devnet-report.json`. The PowerShell commands above reproduce the runs. No mocked responses were used. The production profile and unfinished business/live-capture APIs were not tested.

## Documentation

The console additions were checked for JSON parsing and JavaScript script syntax on 2026-10-03. They have not been run against a local application or provider during this feature close-out. The six-request application results above describe the earlier Actuator-only collection.

- [Postman test scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/test-scripts)
- [Newman CLI](https://learning.postman.com/docs/reference/newman-cli/command-line-integration-with-newman)
- [Solana HTTP RPC methods](https://solana.com/docs/rpc/http)
