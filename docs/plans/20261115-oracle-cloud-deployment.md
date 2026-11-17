# Oracle Cloud deployment

> Run the always-on research instance (automation, captures, console, PostgreSQL) on one Oracle Cloud Always Free Arm VM,
> reachable only through an SSH tunnel, so it keeps watching the wallet while the development PC is off.

Status: Approved (2026-11-16). Parts 1 and 2 are implemented and reviewed (uncommitted), amended the same day to run the
server as `prod` with `update`. Parts 3–6 are on the server and
start once the developer has created the Oracle account (deploy/README.md steps 1–12).

## Context

Today the only running instance is the developer's PC: `bootRun` (profile `local`) plus the Compose PostgreSQL on
`127.0.0.1:5433`. `application-local.yml` turns on the console and automation, which watches the wallet with `poll-always`.
When the PC sleeps, discovery stops and buys are missed.

What the deployment has to work with:

- **Profiles.** Captures (`CaptureConfiguration`, `CaptureController`, `CaptureExceptionHandler`) and automation
  (`AutomationConditions`) exist only under `@Profile("local & !prod")`. The `prod` profile (`application-prod.yml`) has the
  datasource from the environment and `ddl-auto: validate`, and is meant to run with the console disabled. So `prod` cannot
  run what we want on the server.
- **Access.** The console has no authentication. `LocalAccessFilter` lets capture routes, `/api/wallets` and dataset
  deletion through only for a loopback peer, a local `Host` and a local `Origin`. The architecture review
  ([F5](../architecture/20261112-architecture-review.md)) found that the filter can be bypassed with encoded paths and that
  most state-changing routes (backfills, retries, run start, uploads) are not guarded at all. Exposing port 8081 is
  therefore not safe.
- **Schema.** There is no Flyway; `local` runs `ddl-auto: update` (CLAUDE.md, Database schema).
- **Capture evidence** lives in files under `trenching.console.capture.root-directory` (`CaptureFileStore`), not in the
  database. The default is `build/captures`.
- **Packaging.** `gradlew build -PwithConsole` bundles `frontend/dist` into the jar under `/console/`.
- **Oracle Always Free (checked 2026-11-15, [Oracle docs](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm)).**
  - Ampere A1: 1,500 OCPU hours and 9,000 GB hours a month. That is **2 OCPU and 12 GB** running all month; it was 4 / 24
    before the 2026 cut.
  - 200 GB of block storage in total (boot volumes included), plus 5 volume backups.
  - 10 TB of outbound traffic a month.
  - Idle reclamation: an instance can be reclaimed when, over 7 days, 95th-percentile CPU < 20 %, network < 20 % **and**
    memory < 20 % (memory counts on A1 shapes only).

## Scope

### In scope

- One A1 VM with PostgreSQL 17, the backend jar with the bundled console, and capture storage.
- The `prod` profile made fit for the server, plus deploy artifacts under `deploy/`.
- A one-time migration of the local database and capture root, and the cut-over rule that leaves exactly one automation
  instance.
- Nightly backups, plus one restore drill.

### Out of scope

- Public HTTPS, a domain, authentication, or access for anyone but the developer. Each of these needs F5 fixed first.
- CI/CD deployment from GitHub Actions. This plan deploys by hand with a script; push-to-deploy is planned in [20261116-github-actions-deploy.md](20261116-github-actions-deploy.md).
- Flyway or any schema tooling change.
- Telegram or other alerting (that module is deferred).
- Any change to the strategy, capture or automation behaviour.

## Key Decisions

1. **The server takes over the live instance; the PC becomes a development box.** The value of a server here is 24/7
   discovery and capture, so the server runs automation, captures and the console.
   - Rejected: a read-only results viewer under `prod`. Captures and automation do not exist there, and it would not fix the
     missed buys.

2. **The server runs the `prod` profile; the PC runs `local`.** (Amended 2026-11-16 at the developer's request; it replaces
   the first version's `local,server` overlay and `application-server.yml`.)
   - The capture and automation gates change from `@Profile("local & !prod")` to `@Profile("local | prod")`. That affects
     `CaptureConfiguration`, `CaptureController`, `CaptureExceptionHandler` and `AutomationConditions`.
     `AutomationHistoryStartRunner` becomes `(local | prod) & !test`. The `test` profile still has neither.
   - Settings both profiles need move out of `application-local.yml` into `application.yml`: the Binance mirror URL, the
     Helius pacing (2 in flight, 250 ms) and the log rolling policy. No test may start talking to a live provider because of
     that move. Any switch that would do so (such as `reference-rates.enabled`) is set in `local` and `prod` only.
   - `application-prod.yml` keeps its environment-only datasource (`DATABASE_URL`, `DATABASE_USERNAME`,
     `DATABASE_PASSWORD`, with no fallback). It adds:
     - the console enabled, and `server.address: 127.0.0.1`;
     - capture root, log file and disk-space health under `/data`;
     - INFO logging and `show-sql: false`;
     - the automation settings stated explicitly with `enabled: false`. The single on-switch is
       `TRENCHING_AUTOMATION_ENABLED=true` in the server's environment file.
   - Rejected: the `local,server` overlay. It avoided a code change, but it kept the server on a profile named for the PC.

3. **Access only through an SSH tunnel.**
   - The backend binds 127.0.0.1:8081 and PostgreSQL binds 127.0.0.1:5432. The OCI security list and the host firewall open
     only TCP 22. The home IP is not fixed, so 22 stays open to all, with key-only login, no password or root login, and
     fail2ban.
   - The developer runs `ssh -L 8081:127.0.0.1:8081 <server>` and opens `http://localhost:8081/console/`.
   - Through the tunnel the request arrives from a loopback peer with `Host: localhost:8081` and a matching `Origin`. So
     `LocalAccessFilter` passes unchanged, and the F5 bypasses are unreachable because nothing but loopback can reach 8081.
   - Rejected for now: Tailscale. The peer is a 100.x address, so the filter would refuse captures.
   - Rejected for now: a public reverse proxy with basic auth. It needs the F5 fix.
   - Never run `npm run serve:public` (the jprq/ngrok tunnel) on the server.

4. **PostgreSQL in Docker, the app as a systemd service.**
   - PostgreSQL: `deploy/compose.server.yml` runs `postgres:17-alpine` (multi-arch, so arm64 works), with data on
     `/data/postgres`, published on 127.0.0.1 only.
   - The app: a `trenching-bot.service` unit runs the jar on Temurin 21 JRE (arm64). It has `Restart=on-failure`, starts
     after Docker, uses `WorkingDirectory=/opt/trenching-bot` and runs as a dedicated `trenching` user.
   - Why systemd: journald logs, simple restarts, and no image registry. The jar is architecture-neutral, so it is built on
     the PC.

5. **VM shape: `VM.Standard.A1.Flex`, 2 OCPU / 12 GB, Ubuntu 24.04 aarch64, in home region Frankfurt
   (`eu-frankfurt-1`).** Always Free capacity exists only in the home region, which is fixed at sign-up. Frankfurt is close
   to Helius and Binance in Europe. The account is upgraded to Pay As You Go with a $1 budget alert: the A1 allowance stays
   free, and PAYG usually gets past "out of capacity" errors when creating the instance. Storage is a 50 GB boot volume plus a 100 GB block volume mounted at `/data` (postgres,
   captures, logs, backups). That totals 150 of the 200 GB, and the data survives an OS rebuild.

6. **Keep the instance out of idle reclamation.** Reclamation needs all three metrics below 20 %, and memory is the one we
   control. The JVM runs with `-Xms3g -Xmx4g -XX:+AlwaysPreTouch`, and PostgreSQL with `shared_buffers=1GB`. Resident memory
   then stays above 2.4 GB (20 % of 12 GB). See Open Question 2 for the stronger option.

7. **Exactly one automation instance.** The rolling `max-requests-per-24h` cap is counted per database
   (`api_usage_buckets`), so two instances would each spend up to the cap from the same Helius key, and would capture the
   same buys twice. After cut-over:
   - the server is the only instance with automation on;
   - the PC runs with `TRENCHING_AUTOMATION_ENABLED=false`, or against a scratch database. Any start that does not override
     the profile is the live local instance (architecture review F6).

8. **Secrets live in one root-owned environment file that the developer creates on the server:**
   `/etc/trenching-bot/trenching-bot.env` (mode 0600). systemd (`EnvironmentFile=`) and Compose (`env_file:`) both read it.
   Agents never read it (CLAUDE.md). Variable names:
   - `SOLANA_RPC_URL`
   - `DATABASE_USERNAME`
   - `DATABASE_PASSWORD`

   Compose maps `POSTGRES_USER` and `POSTGRES_PASSWORD` from the two database variables. Generate a new database password;
   do not reuse the committed `trenching`.

9. **Deploy by script from a clean commit.**
   - `deploy/deploy.ps1` (Windows, OpenSSH) refuses a dirty working tree, because the jar bakes in `application-local.yml`
     and that file is modified locally right now.
   - It runs `gradlew build -PwithConsole`, copies the jar to `/opt/trenching-bot/releases/<shortsha>.jar`, repoints the
     `current.jar` symlink, restarts the service and waits for `/actuator/health` to report `UP` over SSH.
   - Rollback means repointing the symlink to the previous release.

10. **`prod` runs `ddl-auto: update`, the same as the PC; the schema is never reset.** (Amended 2026-11-16: `prod` used
    to only validate. The developer chose `update`, and CLAUDE.md changes with it.)
    - On start, Hibernate adds new tables and columns, plus the unique keys and indexes declared on entities.
      `SchemaDriftConfiguration`, which runs only under `update`, now also repairs generated CHECK drift in `prod`. That
      database is a copy of the PC's, so it gets the same repairs.
    - A destructive entity change (a drop, rename, narrowing or retype) is applied to the server database by hand
      **before** deploying. Never run `docker compose down -v` there.

11. **Live settings leave the tracked `application-local.yml`.** The tracked file goes back to automation off. The PC's
    personal settings (automation on, `poll-always`, the wallet, the request cap) move to an untracked
    `config/application-local.yml` in the project root, which `.gitignore` excludes. Spring Boot loads `./config/` from the
    working directory after the classpath file, so `bootRun` and the IDE pick it up with no code change. A fresh clone then
    starts with automation off (architecture review F6), and `deploy.ps1` can build from a clean tree.

12. **Backups.**
    - A systemd timer runs `pg_dump -Fc` nightly into `/data/backups`, keeping 7.
    - A weekly OCI block-volume backup of `/data`, keeping 4, fits within the 5 free backups.
    - The developer copies one dump to the PC each week (`scp`).

## Module Contracts

No module contract changes. No ports, value types or REST endpoints change. Configuration only.

## Domain Rules

The trading-domain invariants (BigDecimal, slots, determinism, idempotency, read-only toward Solana) do not change: the same
code runs on another host. These are the deployment invariants:

1. On the server, only port 22 accepts connections from outside the host. 8081 and 5432 listen on 127.0.0.1 only.
2. A start under `prod` without `DATABASE_URL`, `DATABASE_USERNAME` or `DATABASE_PASSWORD` fails fast; there is no
   fallback credential.
3. The server's effective settings come from `application.yml`, `application-prod.yml` and the environment, never from
   `application-local.yml`. Captures and automation exist under `local` and `prod`, never under `test` alone.
4. At most one instance with `trenching.automation.enabled=true` exists at any time, across PC and server.
5. A deploy from a dirty working tree is refused.
6. A failed health check after a deploy leaves the previous release available, by symlink, for a one-command rollback.
7. Times: the server OS runs in UTC. Hibernate already uses UTC, and automation uses its explicit `zone: "+05:00"`, so the
   host timezone changes no result.
8. Negative cases:
   - The server reboots: Docker and the service come back unattended, and recovery-enabled capture operations resume
     within their saved limits (existing behaviour).
   - Disk full on `/data`: PostgreSQL stops accepting writes. The backup job checks free space and fails loudly rather than
     deleting older dumps first.
   - Helius rate limit: unchanged, with the shared pacing in `application.yml` (2 in flight, 250 ms).
   - `SOLANA_RPC_URL` unset: the console shows "Not configured" and automation does no provider calls (existing behaviour).
   - Binance data mirror unreachable from the Oracle region: rate steps fail as they do today, and the console shows the
     provider error.

## Data Model

No schema change. The migrated database keeps everything the PC database has, including the CHECK constraints and the
partial unique index from [docs/schema/](../schema/) that Hibernate does not create. `pg_restore` copies them.

## Transactions & Integration

- Provider calls are unchanged (Helius RPC, Binance public data). Outbound traffic is far below 10 TB.
- Additions to `application-prod.yml` (the datasource stays `${DATABASE_URL}`, `${DATABASE_USERNAME}` and
  `${DATABASE_PASSWORD}`; the systemd unit sets the non-secret
  `DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/trenching_bot`):

  | Key | Value |
  | --- | --- |
  | `spring.jpa.hibernate.ddl-auto` | `update` (was `validate`) |
  | `spring.jpa.show-sql` | `false` |
  | `trenching.console.enabled` | `true` |
  | `management.health.diskspace.path` | `/data` |
  | `marketdata.reference-rates.enabled` | `true` (unless it moves to `application.yml` safely) |
  | `server.address` | `127.0.0.1` |
  | `trenching.console.capture.root-directory` | `/data/captures` |
  | `logging.file.name` | `/data/logs/trenching-bot.log` |
  | `logging.level.com.trenching.bot` | `INFO` |
  | `trenching.automation.*` | `capture-enabled`, `poll-always`, `wallet`, `max-requests-per-24h` stated explicitly; `enabled: false`, and the single on-switch is `TRENCHING_AUTOMATION_ENABLED=true` in the server's env file, set at cut-over |

## What To Implement

1. **The server profile, test first.** (Amended: the server is `prod`.)
   - Deliverable: `application-prod.yml` as in the table above, the shared settings moved into `application.yml`, and
     `application-server.yml` removed.
   - The gates `local | prod` (`(local | prod) & !test` for the history runner). KDoc that says "local profile only" is
     corrected.
   - A configuration test proving that `prod` resolves the loopback address, the environment datasource with no fallback,
     `ddl-auto: update`, `/data` paths, the explicit automation settings and the env on-switch. Also that `local` alone is
     unchanged, and that captures and automation beans are absent under `test`.
   - Owner: config plus the console gate annotations; no feature behaviour changes.
   - Same part: the tracked `application-local.yml` goes back to automation off. The PC's live settings move to the
     untracked `config/application-local.yml` (Key Decision 11), which is added to `.gitignore`.
2. **The deploy bundle under `deploy/`:**
   - `compose.server.yml`
   - `trenching-bot.service`
   - the JVM options file
   - `deploy.ps1` (clean-tree check, build, copy, switch, health wait, rollback flag)
   - `backup.sh` with its systemd timer
   - `deploy/README.md`, the runbook for provisioning, first install, deploy, rollback, tunnel, backup and restore

   This can be written in parallel with 1; the two touch no common files.
3. **Provisioning, done by the developer in the OCI console with the runbook.** Agents cannot create accounts or sign in.
   - The VCN with a public subnet, and a security list allowing ingress on TCP 22 only (from the developer's IP if it is
     static).
   - The instance, with an SSH public key.
   - The 100 GB block volume, attached and mounted at `/data` through `/etc/fstab` with `nofail`.
4. **Server bootstrap over SSH.**
   - Packages: Docker Engine and the Compose plugin, Temurin 21 JRE (arm64), unattended-upgrades.
   - The `trenching` user, `/opt/trenching-bot`, `/data/{postgres,captures,logs,backups}`.
   - Host firewall: Oracle's Ubuntu images ship iptables rules, so keep 22 and drop the rest.
   - sshd: `PasswordAuthentication no`.
   - The developer creates the environment file. Then start PostgreSQL and deploy the first release with automation **off**.
5. **Migration and cut-over.** Depends on 4.
   1. Stop the PC instance.
   2. `pg_dump -Fc` the local Compose database, and tar the capture root. The root is the default `build/captures`,
      checked 2026-11-15: 4.2 GB, 355 sessions under `console/` plus `transaction-cache/`. No session is an attachment, and no
      session file holds an absolute Windows path, so the folder moves as is. Until it is copied, do not run
      `gradlew clean`, which deletes `build/captures`.
   3. Copy both to the server; run `pg_restore` and untar into `/data/captures`.
   4. Compare row counts per table.
   5. Turn automation on, on the server.
   6. Restart the PC only with `TRENCHING_AUTOMATION_ENABLED=false`.
6. **Backups and a restore drill.** Depends on 5. Enable the timer and the volume backup policy. Restore last night's dump
   into a scratch database on the server and compare counts.

## Verification

- Unit test (`.\gradlew.bat unitTest`): the `prod` configuration test from part 1. It checks the bound properties, and that
  a missing `DATABASE_PASSWORD` fails at startup.
- Full suite: `.\gradlew.bat build -PwithConsole` passes; it is also the artifact being deployed.
- On the server, after part 4:
  - `ss -tlnp` lists only `0.0.0.0:22` / `[::]:22` as non-loopback listeners.
  - From the PC, connecting to `<public-ip>:8081` and `<public-ip>:5432` times out.
- Through the tunnel:
  - `/actuator/health` reports `UP` with the database component.
  - `/console/` loads.
  - The capture list (`GET /api/captures`) returns 200, not 403 `LOCAL_ACCESS_REQUIRED`.
- A reboot (`sudo reboot`) brings PostgreSQL and the app back without intervention.

## Documentation Sync

- `README.md`: a short "Server deployment" section that links to `deploy/README.md`, the `prod` profile, and the
  one-automation-instance rule. Correct the console row ("keep disabled in production") and the profile/schema paragraph.
- `CLAUDE.md`, Database schema: `prod` now runs `update` too; destructive changes are applied by hand before deploying.
- `docs/architecture/20261112-architecture-review.md`: note under F5 that the server keeps 8081 on loopback behind SSH, so
  the exposure finding stays open only for the PC and any future non-tunnel access.
- An ADR is not needed: nothing here changes simulation fidelity, reproducibility or contracts.

## Acceptance Criteria

1. `systemctl is-active trenching-bot` reports `active` on the server, and the service survives a reboot.
2. From the PC, only port 22 of the server's public IP answers.
3. Through the SSH tunnel, the console loads and a capture route returns 200.
4. After migration, the row counts of `replay_datasets`, `simulation_runs`, `positions` and the automation tables are equal
   on the PC dump and the server.
5. The automation status on the server shows discovery ticks during a period when the PC is off, and the PC instance
   reports automation disabled.
6. `/data/backups` holds a dump from the last 24 hours, and that dump restores into a scratch database with equal counts.
7. `deploy.ps1` refuses a dirty tree, and `deploy.ps1 -Rollback` returns the previous release to health `UP`.
8. Over 7 days, OCI metrics show memory utilisation above 20 %.

## Open Questions

1. ~~Do you want the server to run the live automation (the full instance), or only to show results?~~
   **Decided 2026-11-15: the full live instance** (automation, captures and console), as in Key Decision 1.
2. ~~Always Free only, or Pay As You Go?~~ **Decided: Pay As You Go**, with a $1 budget alert (Key Decision 5). The memory
   floor in Key Decision 6 stays, because Oracle's page does not say PAYG instances are exempt from reclamation.
3. ~~Account and home region?~~ **Decided: no account yet; home region Frankfurt (`eu-frankfurt-1`).**
4. ~~Capture root and attachments?~~ **Checked:** the default `build/captures`, 355 sessions, no attachments, no absolute
   Windows paths. It moves as is (part 5).
5. ~~Static home IP?~~ **Decided: not fixed.** Port 22 stays open to all, with key-only login and fail2ban (Key Decision 3).
6. ~~The uncommitted `application-local.yml` change?~~ **Decided: move it to an untracked override** (Key Decision 11).
7. ~~Fix F5 before go-live?~~ **Decided: separately, later.** Not needed while 8081 is reachable only through SSH. It must be
   fixed before any Tailscale, LAN or public access.

No questions remain open.
