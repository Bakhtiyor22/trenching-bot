# Postman API checks

Import both the collection and its environment from this directory into Postman. The application and Solana collections are independent. All requests are read-only, including the two JSON-RPC POST requests.

## Test the local application

1. From the repository root, start PostgreSQL with `docker compose up -d` and the application with `.\gradlew.bat bootRun` (or run it from IntelliJ).
2. Import `trenching-bot.postman_collection.json` and `local.postman_environment.json`.
3. Select **Trenching Bot — Local**. The default `baseUrl` is `http://localhost:8080`; update it if `SERVER_PORT` differs. Use a URL without a trailing slash.
4. Open **Trenching Bot — Application API**, then **Run collection**. Run all six requests. Assertions appear in the test results.

| Request | Verifies |
| --- | --- |
| GET /actuator | Exposed health/info links |
| GET /actuator/health | Overall UP and local PostgreSQL component UP |
| GET /actuator/health/liveness | Process liveness |
| GET /actuator/health/readiness | Spring readiness state |
| GET /actuator/info | Valid JSON object; `{}` is expected today |
| GET /actuator/health/db | Local database UP and PostgreSQL product name |

All requests also check HTTP 200, JSON media type and object response. Actuator's vendor JSON media type is accepted. An unhealthy app/database should fail assertions; do not change expected UP to conceal a failure. Liveness/readiness alone do not verify database connectivity.

For a production target, duplicate the environment, change `baseUrl`, set `expectHealthDetails` to `false`, and run only the **Application endpoints** folder. The production profile hides health details, so the **Local only — PostgreSQL details** folder is not applicable. Production execution has not been verified.

## Optional public Solana devnet checks

Import `solana-devnet.postman_collection.json` and `solana-devnet.postman_environment.json`. Select **Trenching Bot — Solana Devnet**, then run **Trenching Bot — Solana Devnet smoke**. Its `rpcUrl` defaults to `https://api.devnet.solana.com`. No registration, credentials, wallet or billing setup is needed for these public requests.

`getVersion` checks the node version; `getSlot` checks a positive confirmed slot. JSON-RPC errors fail assertions even when HTTP status is 200. Public RPC connectivity and rate limits may cause failures; inspect the response/error and rerun later. Do not store private provider URLs/API keys in exported environments or Git; use Postman Vault or private local values if switching providers.

## Run from PowerShell

With Node.js/npm installed, from the repository root:
```powershell
npm.cmd exec --yes --package=newman@6.2.2 -- newman run postman/trenching-bot.postman_collection.json -e postman/local.postman_environment.json --timeout-request 15000
npm.cmd exec --yes --package=newman@6.2.2 -- newman run postman/solana-devnet.postman_collection.json -e postman/solana-devnet.postman_environment.json --timeout-request 20000
```
Newman is downloaded to npm's cache; it is not a project dependency. A nonzero exit status indicates a failed request or assertion.

## Coverage and limits

The current app exposes Actuator health/info only. Replay, trade intents, fills and positions do not yet have REST endpoints; use the replay commands described in the root README. These collections do not invent those endpoints. Application checks exercise the running app and its PostgreSQL connection. Solana checks call devnet directly and do not validate the unfinished live wallet/market integrations. No mocks, signed transactions, airdrops or trading requests are included.

## Verified on 2026-10-02

Executed with Newman 6.2.2 against the running local application and the real public Solana devnet endpoint:

| Collection | Requests passed | Assertions passed | Failures |
| --- | --- | --- | --- |
| Application API, local profile with PostgreSQL | 6/6 | 25/25 | 0 |
| Solana Devnet smoke | 2/2 | 14/14 | 0 |

Devnet returned Solana core `4.3.0` and confirmed slot `506545461`; these are observations from that run, not pinned assertions. Both collections also passed the official Postman v2.1 JSON schema validation. Environment files parsed successfully and were used in the live runs.

Newman JSON reports are in ignored `build/postman/application-report.json` and `build/postman/solana-devnet-report.json`. The PowerShell commands above reproduce the runs. No mocked responses were used. The production profile and unfinished business/live-capture APIs were not tested.

## Documentation

- [Postman test scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/test-scripts)
- [Newman CLI](https://learning.postman.com/docs/reference/newman-cli/command-line-integration-with-newman)
- [Solana getVersion](https://solana.com/docs/rpc/http/getversion)
- [Solana getSlot](https://solana.com/docs/rpc/http/getslot)
