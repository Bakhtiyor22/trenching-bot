# Readable backend logs

> One message style, one meaning per level, and the automation's state said out loud: what it found, captured, skipped,
> failed and why, and when the request cap holds it, so a person scanning the journal or Grafana Loki can follow the bot.

Status: Delivered (2026-11-21)

## Context

The live instance logs to stdout, so to the systemd journal, and Grafana Alloy ships the journal to Grafana Cloud Loki
(`deploy/alloy/config.alloy`: it scrubs credential-shaped strings, joins stack traces on `^\d{4}-\d{2}-\d{2}T` and takes
the level from `^\S+\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s`). `prod` logs `com.trenching.bot` at INFO; `local` at DEBUG, also
to `logs/trenching-bot.log`. There is no `logback*.xml`; the format is Spring Boot's default.

This week the automation fell three days behind because the 100,000-requests-per-24h cap was used up, and the logs did
not show it. Reading the 161 `log.*` calls under `src/main/kotlin/com/trenching/bot` (76 WARN, 64 INFO, 19 ERROR,
2 DEBUG) explains why:

1. **The cap said it once per item, then went silent.** `AutomationPipelineService.acquire` logs
   `automation request cap reached itemId=… step=… backfillId=… requestsLast24h=… maxRequestsPer24h=… reserveRequests=…`
   only when an item first turns `BLOCKED_BUDGET`. A backlog of 37 items gave 37 WARN lines in one second, each followed
   by `automation item advanced … state=BLOCKED_BUDGET` at INFO, then nothing for three days: no reminder, no "resumed".
   `NOT_CONFIGURED` has the opposite problem: one WARN every tick (every minute) until configured.
2. **The reason for a failure is not logged.** A capture operation that ends `INCOMPLETE` (code `REQUESTS`, `OVER_CAP`, …)
   logs nothing in `CaptureOperationService`; only thrown failures log. The automation then logs
   `automation item advanced itemId=… step=MARKET state=FAILED`: no code, no mint, no meaning. A failed attempt that will
   be retried reads `state=CAPTURING`, like progress.
3. **Every bookkeeping change is INFO.** `AutomationPipelineTransaction.record` logs `automation item advanced` on every
   change of every item (session created, step started, step finished, waiting, blocked, …): about a dozen INFO lines
   per buy, all the same shape. Session merge/split/rebuild/remove lines are INFO too.
4. **Idle is not quiet.** In the polling window, discovery logs `automation discovery wallet=… actions=0 items=0
   belowFilter=0 sessionsOpened=0 watermarkAdvanced=false` every 10 minutes, plus one `wallet_activity page …` INFO
   line per page read. Per-window and per-phase lines (`market_capture window`, `market_capture details`,
   `wallet_capture details`, `wallet_capture verification_details`, `wallet_capture token_program`) are INFO, and
   `chain_capture rpc_retry` is a WARN per retry, so a rate-limited capture writes hundreds of WARN lines whose totals
   the phase line already carries (`rateLimitedResponses`, `retries`).
5. **Jargon and missing context.** Messages are internal names (`market_capture window held`, `acquisitionComplete`,
   `watermarkAdvanced`, `coverage=`, `skippedNewer=`, `belowFilter=`, `consecutiveFailures=`) and rarely say what
   happened or what it means. Automation item lines carry `itemId` but not the mint, wallet, buy time or trading day;
   capture lines carry the mint but not the item, so the two halves of one buy cannot be joined by eye. Absent values
   print as `backfillId=null`.
6. **Inconsistent shapes.** Three styles side by side: `automation item advanced …` (words), `market_capture window …`
   (snake_case area + word), `capture_renamed …` / `capture_enrichment unreadable_capture_files …` (snake_case event).
7. **A long prefix.** Each line starts with ~105 characters before the message:
   `2026-11-21T14:00:00.123Z  WARN 4242 --- [trenching-bot] [   scheduling-1] c.t.b.c.a.a.AutomationPipelineService    : `.
   The journal adds its own timestamp, host and PID in front of that.

Owners today: `console/automation/application/*` (discovery, pipeline, sessions, backfills, sweep),
`console/capture/CaptureOperationService.kt` (capture operations), `marketdata/application/*`,
`walletactivity/application/*`, `chaincapture/*` (provider reads), `replay/application/*`, `apiusage/application/*`,
`console/application/*`, `config/schema/*`.

## Scope

### In scope

- A written log style (message shape, key names, levels, volume rule) added to the project's logging conventions.
- A shorter console and file line prefix that Alloy's existing parsing still reads.
- Automation: one line per business event for a buy (found, captured, skipped, retried, failed, abandoned, simulated),
  carrying the buy's context; the step-by-step lines moved to DEBUG.
- Automation holds (request cap for live items, request cap reserve for backfill items, capture not configured): one WARN
  when a hold starts, one INFO when it ends, reminded hourly while it lasts.
- An hourly automation summary line.
- A startup line that states the automation's effective settings.
- Capture console: one line when an operation ends, with its code in plain words.
- Every other `log.*` call under `com.trenching.bot` reworded to the style, with the noisy ones moved to DEBUG.
- Tests for every behaviour change; tests that assert on log text updated.

### Out of scope

- JSON or other structured log output (rejected below), MDC, log shipping changes, Loki dashboards or alerts.
- Changing `deploy/alloy/config.alloy` (the new prefix keeps its regexes working; a test pins that).
- Framework loggers (Spring, Hibernate, Hikari, Tomcat): left at their defaults.
- Metrics. The summary is a log line, not a Micrometer gauge.
- Any change to what the automation does. Only what it says.

## Key Decisions

1. **Message shape: a plain sentence, then `key=value` context.**
   `Buy failed at the market step and will not be retried: the market window lists more than 2000 trades. code=OVER_CAP itemId=… mint=…`
   The sentence says what happened and what it means or what happens next, in words a person who did not write the code
   knows (buy, token, wallet, capture, market, request cap, backfill, session, dataset, sweep). Internal type and field
   names (manifest, watermark, coverage, selection, acquisition) stay out of the sentence. Sentence case, ending with a
   full stop. The context after it is grep- and Loki-friendly: `|= "itemId=…"`, or `| logfmt` (Loki's logfmt parser
   skips the bare words of the sentence).
2. **Key names keep the existing convention** in `errors-and-logging.md`: `itemId`, `sessionId`, `captureSessionId`,
   `backfillId`, `operationId`, `datasetId`, `runId`, `mint`, `wallet`, `signature`, `slot`. No renaming to shorter keys:
   it would break the grep habits and the ids the console and README quote. New keys follow the same camelCase.
3. **Values.** Optional values that are absent are left out, never printed as `=null` (a live buy has no `backfillId`).
   Instants are ISO-8601 UTC (`Instant.toString`). Durations are compact (`2d10h31m`, `35m`, `12s`) through one helper.
   Attempts as `attempt=2/2`. Codes always appear as `code=` and are also explained in the sentence. Values never hold
   spaces.
4. **Levels mean one thing each here.**
   - `ERROR`: a bug or an unexpected failure, with the stack trace. Someone should look.
   - `WARN`: the bot cannot do part of its job, or lost or refused work, and it needs a person or time to clear: a buy
     failed for good, a step failed and is retried, a hold started (request cap, backfill budget, not configured), a
     provider still failing after its retries, discovery skipped, data skipped. Each such state is logged when it starts,
     not on every tick.
   - `INFO`: business events, about three per buy: buy found, buy captured, skipped or simulated, session opened or
     closed, backfill queued or ended, a capture operation that stopped short, a hold ended, the hourly summary, the
     startup line, and what a person did in the console (retry, close, cancel, run started, dataset registered or
     deleted).
   - `DEBUG`: steps, pages, windows, retries, cache events, bookkeeping (session merge, split, rebuild), resolved races,
     scans that found nothing. `local` already logs DEBUG, so nothing is lost on a PC.
5. **Volume rule.** A line that can fire once per page, request, window, retry or tick in normal running is `DEBUG`.
   If its count matters, a once-per-operation or once-per-hour line carries the count. With nothing happening, prod
   logs nothing at INFO except the hourly summary, and the summary is skipped when nothing happened and nothing waits.
6. **Log once per layer that adds meaning; the layer above logs the consequence, not the cause again.** A buy's step
   passes through three layers: the market or wallet capture (`marketdata`, `walletactivity`), the capture console
   operation, and the automation item. The capture modules' per-operation `finished` lines become DEBUG; the capture
   console logs one line when an operation stops short and why (`INFO`, with its code and requests; DEBUG when it
   succeeded); the automation logs what that means for the buy (`WARN`: retried or failed). So a failed step is one
   INFO and one WARN, and a captured buy is three INFO lines. The RPC client logs a provider still failing after its
   retries (`WARN`); its per-retry lines are DEBUG.
   Detail is not lost: `local` logs DEBUG, and on the server one package can be turned up without a code change
   (`LOGGING_LEVEL_COM_TRENCHING_BOT_MARKETDATA=DEBUG` in the service's environment file, then a restart).
7. **Item lines come from one place.** Every item change already goes through `AutomationPipelineTransaction.record`
   (plus the session close in `AutomationSessionTransaction` and the item creation in discovery and backfill). One
   `AutomationItemLog` component turns a change (before, after, trace, the item's mint, wallet, block time, backfill)
   into its line and level, so the wording, the context keys and the level rule live in one tested place instead of
   eleven call sites.
8. **Holds are tracked per tick, not per item.** The pipeline already decides per item; it now reports the tick's held
   and admitted items to one `AutomationHolds` component, which keeps each hold's start time in memory and logs only
   on a change: one WARN when a hold starts, one INFO with its duration when it ends. The hourly summary repeats any
   hold that lasts. After a restart a hold still in force is logged again on the first tick, which is what a reader of
   the new process wants.
9. **Hourly summary.** One `AutomationActivitySummary` line per hour while automation is enabled:
   what happened in the hour (buys found, captured, skipped, failed by code, simulated, retried), what waits now (items by
   state, from one grouped count), and the requests used in the last 24 hours against the cap. It is `WARN` while a hold
   lasts, so `level="WARN"` in Loki keeps showing a stuck bot every hour, and `INFO` otherwise. The hour's counts are
   kept in memory by `AutomationItemLog` as it logs, so they reset on a restart; the first summary after a restart says
   so (`since=` the start).
10. **A shorter prefix, same parse.** Console and file pattern
    `%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p [%t] %logger{0}: %m%n%wEx` (with Spring's `%clr` colours, which systemd's
    non-terminal output drops). It drops the PID (the journal has it), the application name (one app) and the package
    path; the thread stays (`scheduling-1` is the automation, `capture-console-worker` the capture console). Alloy's
    multiline and level regexes still match, so `config.alloy` does not change.

Rejected:

- **JSON / Spring Boot structured logging** (`logging.structured.format.console`): Loki would parse it, but
  `journalctl -u trenching-bot` would show JSON, which is the opposite of the request, and Alloy's level and multiline
  stages would need rewriting. Revisit if Loki queries outgrow `|=` and `| logfmt`.
- **MDC to tag low-level lines with the item**: the capture console runs operations on its own worker thread and on
  manual use too, so the item would have to be copied across threads and is absent half the time. The mint, already on
  both sides, joins them.
- **A reminder per hold separate from the summary**: two lines per hour saying the same thing.

## Before and after

Real lines from today's code, with ids shortened to `…` here only; real lines keep full ids.

**The request cap fills up** (this week). Before: 37 pairs like this in one second, then nothing for three days.

```
2026-11-18T22:41:07.512Z  WARN 4242 --- [trenching-bot] [   scheduling-1] c.t.b.c.a.a.AutomationPipelineService    : automation request cap reached itemId=3f9a… step=MARKET backfillId=null requestsLast24h=100012 maxRequestsPer24h=100000 reserveRequests=5000
2026-11-18T22:41:07.530Z  INFO 4242 --- [trenching-bot] [   scheduling-1] c.t.b.c.a.a.AutomationPipelineTransaction : automation item advanced itemId=3f9a… step=MARKET state=BLOCKED_BUDGET
```

After: one line when it starts, a reminder every hour, one line when it ends.

```
2026-11-18T22:41:07.512Z  WARN [scheduling-1] AutomationHolds: Capture paused: the 24-hour request cap is used up; waiting buys go on as older requests age out. requestsLast24h=100012 cap=100000 waiting=37
2026-11-18T23:41:07.004Z  WARN [scheduling-1] AutomationActivitySummary: Last hour: capture paused by the request cap for 1h; 2 buys found, 0 captured, 39 waiting. found=2 captured=0 skipped=0 failed=0 waitingForCap=39 inCapture=0 requestsLast24h=100020 cap=100000
2026-11-21T09:12:00.001Z  INFO [scheduling-1] AutomationHolds: Capture resumed: the request cap has room again after 2d10h31m. requestsLast24h=99410 cap=100000 waiting=212
```

**A market window over the trade cap** (the `OVER_CAP` fix). Before: the code is logged nowhere.

```
… INFO … c.t.b.m.a.MarketHistoryCaptureService    : market_capture window mint=9wQp…pump fromSlot=381200417 toSlot=381209417 coverage=OVER_CAP scanned=2412 skippedFailed=3 skippedNewer=0 selected=0
… INFO … c.t.b.m.a.MarketHistoryCaptureService    : market_capture finished windows=1 transactions=0 acquisitionComplete=false complete=false requests=13 bytes=812345 stopReason=null
… INFO … c.t.b.c.a.a.AutomationPipelineTransaction : automation item advanced itemId=3f9a… step=MARKET state=FAILED
```

After:

```
… INFO [capture-console-worker] CaptureOperationService: Capture step stopped: the market window lists more than 2000 trades, so none were fetched. code=OVER_CAP step=market requests=13 mint=9wQp…pump captureSessionId=… operationId=…
… WARN [scheduling-1] AutomationItemLog: Buy failed at the market step and will not be retried: the market window lists more than 2000 trades. code=OVER_CAP attempt=1/2 requestsUsed=1841 itemId=3f9a… mint=9wQp…pump wallet=4vw5…
```

**A quiet night.** Before, every 10 minutes inside the polling window:

```
… INFO … c.t.b.w.a.RecentWalletActivityService     : wallet_activity page wallet=4vw5… limit=20 scanned=20 actions=0 unreadable=0 requests=21 cacheHits=0
… INFO … c.t.b.c.a.a.AutomationDiscoveryService    : automation discovery wallet=4vw5… actions=0 items=0 belowFilter=0 sessionsOpened=0 watermarkAdvanced=false
```

After: both DEBUG; nothing at INFO. When the scan finds a buy:

```
… INFO [scheduling-1] AutomationItemLog: Buy found. buyAt=2026-11-20T21:14:03Z tradingDay=2026-11-20 sessionId=… itemId=3f9a… mint=9wQp…pump wallet=4vw5…
… INFO [scheduling-1] AutomationDiscoveryService: Discovery found 1 new buy. read=3 belowFilter=2 pages=1 requests=21 wallet=4vw5…
```

**A buy going through capture.** Before: about twelve lines per buy, all `automation item advanced itemId=… step=… state=…`.
After, at INFO, a captured buy is:

```
… INFO [scheduling-1] AutomationItemLog: Buy found. …
… INFO [scheduling-1] AutomationItemLog: Buy captured and registered as a dataset; the sweep runs next. datasetId=… requestsUsed=1834 itemId=3f9a… mint=9wQp…pump wallet=4vw5…
… INFO [scheduling-1] AutomationItemLog: Buy simulated: every sweep configuration ran on it. runs=24 itemId=3f9a… mint=9wQp…pump wallet=4vw5…
```

and a step that fails once:

```
… INFO [capture-console-worker] CaptureOperationService: Capture step stopped: the operation's request budget ran out. code=REQUESTS step=market requests=4000 mint=9wQp…pump captureSessionId=… operationId=…
… WARN [scheduling-1] AutomationItemLog: Buy capture will retry the market step: the operation's request budget ran out. code=REQUESTS attempt=1/2 itemId=3f9a… mint=9wQp…pump wallet=4vw5…
```

**A rate-limited provider.** Before: one WARN per retry, hundreds per capture.

```
… WARN … c.t.b.chaincapture.HttpSolanaReadRpc      : chain_capture rpc_retry operation=getTransaction category=RATE_LIMITED cause=HTTP_429 attempt=1
```

After: per retry DEBUG; still failing after the last retry is one WARN:

```
… WARN [capture-fetch-3] HttpSolanaReadRpc: RPC call failed after 3 attempts: the provider is rate limiting. method=getTransaction category=RATE_LIMITED cause=HTTP_429 attempts=3
```

**An unexpected error.** Before: `automation item step failed itemId=3f9a…` plus the stack. After:
`ERROR [scheduling-1] AutomationPipelineService: Unexpected error while moving a buy on; the tick goes on with the other buys. step=market itemId=3f9a… mint=9wQp…pump`
plus the stack.

## Module Contracts

No port, cross-module value type or replay fixture changes. All new types are internal to their module.

| Type | Package | Role |
|---|---|---|
| `AutomationItemLog` (`@Component`) | `console.automation.application` | `logged(change: AutomationItemLogEvent)`: one line per item change at the level the style gives it; counts the hour's events for the summary |
| `AutomationItemLogEvent` | `console.automation.domain` | before/after `AutomationItemProgress`, `AutomationItemTrace`, `itemId`, `mint`, `wallet`, `blockTime`, `backfillId`, `requestsUsed`, `datasetId` |
| `AutomationHolds` (`@Component`) | `console.automation.application` | `observe(tick: AutomationHoldTick, now)`: per hold kind, held items and admitted items this tick; logs starts and ends; `current(): List<AutomationHold>` for the summary |
| `AutomationHoldKind` enum | `console.automation.domain` | `REQUEST_CAP` (live items), `BACKFILL_RESERVE` (backfill items held to keep the reserve free), `NOT_CONFIGURED` |
| `AutomationActivitySummary` (`@Service`) | `console.automation.application` | `log(now)`: the hourly line; called by `AutomationScheduler` every hour |
| `AutomationStartupLog` (`@Component`) | `console.automation.config` | logs the effective settings once on `ApplicationReadyEvent` |
| `AutomationFailures.meaning(code)` | `console.automation.domain` (existing object) | the plain sentence for each failure code |
| `CaptureStopMeanings` | `console.capture` | the plain sentence for each capture stop code (`REQUESTS`, `BYTES`, `OVER_CAP`, `HISTORY_NOT_EXHAUSTED`, `RATE_LIMITED`, …) |
| `LogDurations.compact(Duration)` | `common` | `2d10h31m`; shared by every module |
| `AutomationStepOutcome.Held(kind)` | `console.automation.domain` (existing sealed type) | replaces the per-item cap WARN; `NotConfigured` folds into it |
| `AutomationItemRepository.countByState(states)` | `console.automation.infrastructure.persistence` | grouped count for the summary; `state` leads `idx_automation_items_due` |

## Domain Rules

Message and level rules (each testable on the logged event):

1. Every line written by `com.trenching.bot` code is a sentence ending in `.` followed by zero or more `key=value`
   pairs; no value is the literal `null`.
2. Every automation item line carries `itemId`, `mint` and `wallet`; a backfill item's also carries `backfillId`; a line
   for a step carries `step` (lower case) and `attempt=n/max`.
3. Item change → level and line (`AutomationItemLog`):
   - found (live): INFO `Buy found.` with `buyAt`, `tradingDay`, `sessionId`; a backfill's: DEBUG (its tick logs a summary).
   - session created, step started, step finished, waiting for the market delay, held, adopted queued operation: DEBUG.
   - attempt failed, another attempt follows: WARN `Buy capture will retry the <step> step: <meaning>.` with `code`.
   - `FAILED`: WARN `Buy failed at the <step> step and will not be retried: <meaning>.` with `code`, `requestsUsed`.
   - `SKIPPED_NOT_FIRST_BUY`: INFO `Buy skipped: <classification meaning>.` with `classification`.
   - `CAPTURED`: INFO `Buy captured and registered as a dataset; the sweep runs next.` with `datasetId`, `requestsUsed`.
   - `SIMULATED`: INFO `Buy simulated: every sweep configuration ran on it.` with `runs`.
   - `ABANDONED`: INFO `Buy abandoned: its session was closed by hand.`
4. An unknown failure code still logs, with the sentence `unexpected code, see the capture console` and the raw code.
5. Holds (`AutomationHolds`), per kind, with an injected `Clock`:
   - first tick with a held item and no hold in force → one WARN naming the hold, `requestsLast24h`, `cap`, `waiting`.
   - later ticks while held → nothing.
   - first tick with an admitted item of that kind while held → one INFO with the hold's duration; the hold clears.
   - a tick with neither held nor admitted items (nothing due) → no change.
   - 100 items held in one tick → exactly one WARN (negative case of today's behaviour).
   - `NOT_CONFIGURED` held on 60 ticks → one WARN, not 60.
   - `REQUEST_CAP` and `BACKFILL_RESERVE` are independent: a backfill held by the reserve while live items are admitted
     logs only the backfill hold.
6. Summary (`AutomationActivitySummary`), with an injected `Clock`:
   - WARN while any hold is in force, naming each hold and its duration so far; INFO otherwise.
   - skipped when the hour counted no event, no item is in a non-terminal state and no hold is in force.
   - counts by state come from one grouped query; requests from `ApiUsageReports` (`LAST_24_HOURS`), as `/status` does.
   - the first summary after start carries `since=<start instant>` instead of a full hour.
7. Capture operation end (`CaptureOperationService`): exactly one line per ended operation: DEBUG for `SUCCEEDED`;
   INFO for `INCOMPLETE`, `FAILED` with a known code, `INTERRUPTED` and `CANCELLED`; ERROR with the replaced stack for
   `CAPTURE_FAILED` (as today). The line carries `captureSessionId`, `operationId`, `step`, `code` (when any),
   `requests`, and `mint` for a market or wallet-token step. It never carries a provider exception message
   (credentials rule, as today).
8. Never logged, unchanged: secrets, tokens, `SOLANA_RPC_URL` or any URL with a query, `Authorization` headers, whole
   vendor payloads, provider exception text. Existing tests that pin this stay green.

## Data Model

No schema change. One read query added: `select state, count(*) from automation_items where state in (:states) group by
state`, served by `idx_automation_items_due` (`state` leading). No entity, column, constraint or index changes.

## Transactions & Integration

- `AutomationItemLog` is called inside `AutomationPipelineTransaction.record` after the change is applied, so it logs
  only a change that is stored in the same transaction. A rollback after the log line is possible only on a commit
  failure, which already logs its own ERROR. Accepted.
- `AutomationHolds.observe` runs once at the end of `AutomationPipelineService.advance`, outside any transaction; it
  reads nothing (the tick passes `requestsLast24h`, already read for the cap check).
- `AutomationActivitySummary.log` runs on the single scheduling thread (`@Scheduled(fixedDelay = 1h, initialDelay = 1h)`
  in `AutomationScheduler`), so it never overlaps the tick. One read-only transaction for the count; no remote call.
- No provider calls, retries or credentials are involved. No new configuration properties: the summary interval is a
  constant (`AutomationLimits.SUMMARY_INTERVAL = 1h`).
- Logging config: `logging.pattern.console` and `logging.pattern.file` in `application.yml` (all profiles, so `prod`
  too). `application-prod.yml` is not edited.

## What To Implement

Ordered; 1 comes first because 2–5 use its helpers and style. 2 and 3 touch different files and can run in parallel;
4 and 5 can run in parallel with each other after 1.

1. **Style and prefix.** The style (Key Decisions 1–6) as a "Log message style" section in
   `.claude/skills/kotlin-spring-guidelines/references/errors-and-logging.md`, with the level table and two examples;
   `LogDurations.compact`; the new console and file pattern in `application.yml`; a test that a logged line matches
   Alloy's two regexes.
2. **Automation buy lines.** `AutomationItemLog` + `AutomationItemLogEvent`, `AutomationFailures.meaning`; wire it into
   `AutomationPipelineTransaction.record`, `AutomationSessionTransaction` (close and abandon), `AutomationDiscoveryTransaction`
   and `AutomationBackfillRecorder` (item created); remove the eleven `automation item advanced` / `automation item
   discovered` call sites. Live discovery: one INFO when it found buys (`Discovery found 2 new buys.` with `read`,
   `belowFilter`, `pages`, `requests`), DEBUG when it found none. Backfill: one INFO per scanned tick. Session lines:
   opened and closed INFO in words; merged, split, rebuilt, removed DEBUG (the README's `automation session state changed`
   line stays INFO, reworded).
3. **Holds and summary.** `AutomationStepOutcome.Held`, `AutomationHolds`, `AutomationActivitySummary`, the hourly
   schedule, `countByState`; remove the per-item cap WARN and the per-tick `NOT_CONFIGURED` WARN; `AutomationStartupLog`.
   The backfill budget line (`automation backfill budget reached`) becomes the item's failure line (`BACKFILL_BUDGET`
   already fails the item).
4. **Capture console and capture modules.** One end-of-operation line in `CaptureOperationService` with
   `CaptureStopMeanings`; `market_capture window`/`window held`/`details`/`extending`/`cutoff`/`finished`,
   `wallet_capture details`/`token_program`/`verification_*`/`finished`, `reference_rates capture finished`,
   `wallet_activity page`, `chain_capture rpc_retry`, `DetailBatches … interrupted`, transaction-cache read misses and
   `reference_rates klines` to DEBUG, each reworded; the `finished` lines name the outcome in words (`complete`,
   `stopped: <meaning>`, `refused: the window lists more than 2000 trades`) for whoever turns DEBUG on. `rpc_failed`,
   `wallet_capture history_not_exhausted`, the cache corruption and the `*_decode finished` lines are reworded at
   their level.
5. **Everything else.** Reword the remaining lines in `replay`, `apiusage`, `console/application`, `console/api`,
   `console/capture` (`capture_renamed`, `capture_deleted`, `capture_enrichment …`), `config/schema` to the style,
   keeping their levels unless the level table says otherwise (`automation item changed meanwhile` and
   `duplicate simulation writer recovered` are resolved races → DEBUG).

## Verification

Test-first for 2–4. Log assertions use a Logback `ListAppender` on the class's logger, as the existing tests do, and
check `level`, `formattedMessage` and, for parameter order, `argumentArray`.

- `AutomationItemLogTest` (unit): each row of Rule 3 → level and message; absent `backfillId` leaves no key; unknown code
  (Rule 4); counts kept for the summary.
- `AutomationHoldsTest` (unit, fixed clock): every bullet of Rule 5.
- `AutomationActivitySummaryTest` (unit, fixed clock): WARN while held, INFO otherwise, skipped when idle, `since=` on
  the first run (Rule 6).
- `AutomationPipelineServiceTest` (existing): 100 items held in one tick log one WARN; an `OVER_CAP` market end logs one
  INFO from the capture console and one WARN `Buy failed at the market step …` with `code=OVER_CAP`; a retried attempt
  logs WARN with `attempt=1/2`.
- `AutomationDiscoveryServiceTest` (existing): an empty scan logs nothing at INFO; a scan with buys logs one discovery
  line and one `Buy found.` per live buy.
- `CaptureOperationService` tests (existing suite): one end line per operation for each end state; `CAPTURE_FAILED`
  keeps the stack and no provider text.
- `LogPatternTest` (Spring, `CapturedOutput`): a WARN line matches `^\d{4}-\d{2}-\d{2}T` and
  `^\S+\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s`, and starts `… WARN [`.
- Updated for new text: `MarketHistoryLeadCaptureTest` (two exact messages), `ConsoleApiIntegrationTest` (argument
  arrays), `SharedTransactionCacheTest`, `AutomationBackfillServiceTest`, `SchemaDriftStartupIntegrationTest` (its
  `REPAIRED` marker), `ConsoleFallbackExceptionResolverTest` (`errorId=` stays). Secret tests unchanged and green.

Commands: `./gradlew test`, then `./gradlew build` (JAVA_HOME set to the IntelliJ JDK).

## Documentation Sync

- `README.md`: the automation paragraphs that quote log lines (`automation item advanced …` in *Waits and retries*,
  `automation session state changed` in *Sessions*, the `NOT_CONFIGURED` "once per tick" sentence, the discovery
  `WARN`s); a short *Logs* paragraph under *Spring application and PostgreSQL* naming the prefix, the levels, the hourly
  summary and two Loki queries (`|= "itemId=…"`, `level="WARN"`).
- `docs/REAL_DATA_CAPTURE.md`: the "each detail phase logs one INFO line" bullet (now DEBUG) and the quoted
  `market_capture …` lines.
- `deploy/README.md`: the *Logs* section gets the hourly summary as the first thing to look for.
- `errors-and-logging.md` (unit 1). No ADR: logging does not affect fidelity, reproducibility or contracts.

## Acceptance Criteria

- With automation enabled and nothing to do, prod writes no `com.trenching.bot` INFO line except the hourly summary, and
  no summary when nothing happened and nothing waits.
- A buy whose capture succeeds first time writes exactly three lines at INFO and above: `Buy found.`,
  `Buy captured …`, `Buy simulated …`; every automation line for it carries its `itemId`, `mint` and `wallet`.
- A failed step writes one capture-console INFO line with the code and one automation WARN line saying whether the buy
  is retried or failed.
- When the request cap holds live capture: one WARN when it starts, a WARN summary every hour naming the hold and its
  duration, one INFO with the total duration when it ends. Never one line per held item.
- An `OVER_CAP` buy writes a WARN that says the market window lists more than `market-max-transactions` trades and
  carries `code=OVER_CAP`.
- A line starts `2026-…Z  WARN [scheduling-1] AutomationHolds: ` and Loki still labels its level.
- `./gradlew build` passes.

## Open Questions

Answered by the user on 2026-11-21:

1. **Line prefix.** Shorter prefix (time, level, thread, class); Alloy untouched. Not Spring's default, not JSON.
2. **Hourly summary.** Yes: WARN when a hold starts, INFO when it ends, one summary per hour, WARN while capture is held.
3. **Buy steps.** Outcomes only at INFO (found, captured, skipped, simulated), WARN for retries and failures, steps DEBUG.
4. **Scope.** Every module in this change (units 1–5).
5. **Alerting (asked after implementation, when the review found that deploy/README.md §14.7 "Discovery stalled" matched
   a line that is now DEBUG).** The hourly summary becomes a heartbeat: it is written every hour while automation is
   enabled, even when nothing happened, and carries `discoveryScans=` (scans run in the hour). This amends Key
   Decision 9 and Domain Rule 6 ("skipped when idle" no longer holds). The runbook's alert becomes "Automation silent"
   (no summary for 2 h) plus "Discovery failing" (`Discovery skipped` WARNs in the last hour).

## Delivered

2026-11-21, uncommitted on `develop` until the user commits. `./gradlew build` green (2290 tests). No schema change; two
read queries added (`AutomationItemRepository.countByState`, `AutomationSweepRunRepository.tally`). No new dependency or
configuration property; `application-prod.yml` untouched.

What shipped, by unit:

1. Style section in `errors-and-logging.md` (rule 55/56 of `strict-rules.md` point at it), `common/LogDurations`,
   `common/LogLine` (sentence plus present `key=value` fields, still parameterized), the shorter prefix in
   `application.yml`, `LogPatternTest` against Alloy's two regexes.
2. Buy lines from one place: `AutomationItemLog` / `domain/AutomationItemLines`, fed by `AutomationItemEventWriter` (every
   item change already passes through it), emitted and counted only after commit (`AfterCommit`).
3. `AutomationHolds`, `AutomationActivitySummary` (hourly), `AutomationStartupLog`.
4. Capture console end line (`CaptureOperationService`), `CaptureStopMeanings` on top of `chaincapture/ProviderFailureMeanings`,
   capture modules re-levelled.
5. Every other module reworded; `replay/domain/CoverageEndedLines` shares the coverage-ended refusal wording.

Where it differs from the plan, and why:

- **Logged after commit**, not inside `record` (Transactions & Integration, first bullet): a rolled-back or conflicting
  change now logs and counts nothing.
- **Holds end after an hour clear** (`AutomationLimits.HOLD_SETTLE`), not on the first admitting tick (Rule 5, third
  bullet): at a full cap each freed batch admits one operation that fills it again, which would log a paused/resumed pair
  per operation. A hold also starts clearing when no due item of its kind is still blocked, so it cannot outlive its items.
- **Hold kinds**: the kind follows the bound that refused (a backfill item refused by the full cap is `REQUEST_CAP`);
  `NOT_CONFIGURED` is split into `RPC_NOT_CONFIGURED` and `RATES_NOT_CONFIGURED`; `BACKFILL_SCAN_RESERVE` is separate
  from `BACKFILL_RESERVE` because the backfill scan checks the reserve elsewhere and would make one shared hold flap.
- **Hourly summary is a heartbeat** (Open Question 5): written every hour while automation is on, idle hours included, with
  `discoveryScans=`. `deploy/README.md` §14.7 replaces "Discovery stalled" with "Automation silent" and "Discovery failing".
- **Replay per-run lines to DEBUG** (run started, decode, planning, export, calibration, qualification finished, dataset
  registered): unit 5 said keep levels, but they fired dozens of times per buy and broke "three INFO lines per buy".
- **One WARN per backfill** when its request budget first stops an item (`BACKFILL_BUDGET` item lines are DEBUG);
  backfill scan failures WARN on the first failure of a run only; RPC failures for answer categories (`SLOT_SKIPPED`,
  `UNSUPPORTED_VERSION`) are DEBUG.
- **Capture console end line** is also INFO for a pause; `marketdata` / `walletactivity` finished lines (now DEBUG) carry
  `code=` instead of the meaning, since those modules cannot import `console.capture`. Phase durations stay in `…Ms`.
- **Startup line** loads with the automation console, so a console with automation off says so.
- **Not proven end to end**: "three INFO lines per captured buy" is proven in two tests (discovery + capture with the
  scripted capture workflow; sweep through the real replay), not one run through real capture and replay together.

Docs updated: `README.md` (Logs paragraph; automation *Waits and retries*, *Sessions*, request cap and holds,
`NOT_CONFIGURED`, backfill budget; the console `errorId` and schema-repair quotes), `docs/REAL_DATA_CAPTURE.md`,
`deploy/README.md` (Logs section, §14.7 alerts), `errors-and-logging.md`, `strict-rules.md`.
