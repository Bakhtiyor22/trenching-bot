# Push-to-deploy with GitHub Actions

> After the build and tests pass on a push to `develop`, a GitHub Actions job builds the jar with the console and
> deploys it to the Oracle server over SSH, rolling back by itself if the new release does not become healthy.

Status: Approved (2026-11-16), with both open questions settled as recommended.

## Interface (fixed before implementation)

- **Release name:** the full 40-character commit SHA in both paths: `github.sha` in Actions, `git rev-parse HEAD` in
  `deploy.ps1`. A hand deploy and an Actions deploy of the same commit are then the same release.
- **Release script:** `/usr/local/bin/trenching-deploy`, run as root, has two modes:
  - `deploy <sha40> <sha256> [--no-auto-rollback] [--file PATH]` reads the jar from `--file`, or from stdin when
    `--file` is absent. It verifies the jar's SHA-256 before installing it.
  - `rollback` repoints to the previous release.
- **SSH wrapper:** `/usr/local/bin/trenching-deploy-ssh` is the forced command of the deploy key and runs as `deploy`.
  - It parses `SSH_ORIGINAL_COMMAND` strictly and accepts only `deploy <40 hex> <64 hex>` or `rollback`.
  - It then execs `sudo /usr/local/bin/trenching-deploy …`, so stdin passes through.
  - The sudoers drop-in allows only that one binary.
- **Actions call:** `ssh … deploy@$DEPLOY_HOST "deploy $GITHUB_SHA $SHA256" < jar`.
- **Hand deploy:** `deploy.ps1` copies the jar to `/tmp` with `scp`, then runs
  `sudo trenching-deploy deploy <sha40> <sha256> --no-auto-rollback --file /tmp/…`. Windows PowerShell 5.1 cannot pipe
  binary data to a native command's stdin.
- **`DEPLOY_ENABLED`:** a **repository** variable. A job's `if:` cannot read environment-level variables. The three
  secrets stay in the `production` environment.
- **`[skip deploy]`:** in the head commit message, it skips the job. Rerunning a skipped job is skipped again. After
  applying the SQL, push an empty commit (`git commit --allow-empty -m "deploy"`) to deploy.

## Context

[The Oracle deployment plan](20261115-oracle-cloud-deployment.md) deploys by hand. `deploy/deploy.ps1` builds on the PC,
uploads the jar over SSH and runs a bash script embedded in `deploy.ps1`, which:
- installs `/opt/trenching-bot/releases/<sha>.jar` and records `previous`;
- switches `current.jar` atomically and restarts `trenching-bot`;
- waits up to 180 s for `/actuator/health` to report `UP`, then prunes old releases (keeping 5);
- offers `-Rollback`.

`.github/workflows/build.yml` runs `build replay` and the frontend checks on every push. It has no deploy step.

The developer wants a push to `develop` to update the server with no manual step (decided 2026-11-16: GitHub Actions,
every green push to `develop`; a Claude cloud routine was rejected).

## Scope

### In scope

- A `deploy` job in `build.yml`.
- One server-side release script shared by the job and `deploy.ps1`.
- A restricted deploy SSH user on the server.
- Automatic rollback on a failed health check.
- Runbook steps for the developer.

### Out of scope

- Deploying other branches or pull requests.
- Blue/green or zero-downtime restarts. A deploy restarts the app, and automation resumes as it does after any restart.
- Database migrations. The schema rule in CLAUDE.md is unchanged.
- Notifications beyond GitHub's own failed-workflow email.

## Key Decisions

1. **Trigger.** The job runs only on `push` to `refs/heads/develop`, with `needs: [build, frontend]`, so it starts only
   when both existing jobs pass for that commit.
   - It is also gated on the repository variable `DEPLOY_ENABLED == 'true'`. Until the server exists and that variable
     is set, the job is skipped, not failed.
   - `concurrency: { group: deploy-prod, cancel-in-progress: false }` keeps two deploys from overlapping; a newer push
     waits for the running one.
   - Pull requests and forks never run it, so they never see its secrets.

2. **Artifact.** The job runs `./gradlew assemble -PwithConsole` on the runner (Temurin 21, Node 24). It does not rerun
   the tests; the `build` job has already run them on the same commit. The jar is architecture-neutral, so building on
   x86 runners for the arm64 VM is fine.

3. **One release script, used by both paths.** The bash script now embedded in `deploy.ps1` moves to
   `deploy/trenching-deploy.sh`, installed on the server as `/usr/local/bin/trenching-deploy`. CLAUDE.md forbids keeping
   two copies of the logic.
   - `deploy.ps1` uploads the jar and calls the installed script instead of sending its own copy.
   - Modes: `deploy <sha40> <sha256>`, which reads the jar from stdin (or `--file`) and verifies its SHA-256, and
     `rollback`. See Interface above.
   - New behaviours, used by both paths:
     - **Idempotent rerun:** if `<sha>` is already `current.jar` and healthy, it exits 0 without restarting.
     - **Backup window:** if `trenching-bot-backup.service` is active, it waits for it to finish (up to 15 minutes, then
       fails), so a deploy never meets the backup's locks.
     - **Unattended auto-rollback:** when the new release does not reach `UP`, it repoints to `previous`, restarts, waits
       for health, prints the journal tail and exits 1. With no `previous` (the first deploy), it only fails.
   - `deploy.ps1` keeps `-Rollback` and passes `--no-auto-rollback`, so a hand deploy still stops for you to look, as it
     does today.

4. **A restricted deploy key, not a shell.**
   - A dedicated server user `deploy` has no password and no sudo except
     `deploy ALL=(root) NOPASSWD: /usr/local/bin/trenching-deploy` (a sudoers drop-in, mode 0440, checked with
     `visudo -c`).
   - Its `authorized_keys` line forces the command: `restrict,command="/usr/local/bin/trenching-deploy-ssh"`.
   - The wrapper, running as `deploy`, accepts only `deploy <40 hex> <64 hex>` or `rollback` from
     `SSH_ORIGINAL_COMMAND`, then execs `sudo -n /usr/local/bin/trenching-deploy …`. The release script validates the
     same arguments again.
   - So a leaked key can only deploy a jar or roll back. It cannot open a shell, forward ports or reach the console
     tunnel.
   - The key is a new ed25519 pair made only for GitHub, never the developer's own key.

5. **Host key pinned.** The job writes `known_hosts` from a secret instead of disabling host-key checking.

6. **GitHub settings, created by the developer.** Agents cannot do this, and must never see the values.
   - Secrets: `DEPLOY_SSH_KEY` (the private key), `DEPLOY_HOST` (the server's public IP), `DEPLOY_KNOWN_HOSTS` (the
     output of `ssh-keyscan -t ed25519 <ip>`, checked against the server's own fingerprint).
   - Variable: `DEPLOY_ENABLED`.
   - They live in a GitHub Environment `production` limited to the `develop` branch, so only that branch can read them.
     The environment has no required reviewers, because the developer chose automatic deploys.

7. **Port 22 stays open to all.** GitHub's runner addresses change, and the plan already keeps 22 open with key-only
   login and fail2ban. The deploy user adds no new open port.

## Module Contracts

None. No application code changes.

## Domain Rules

1. A deploy happens only for a commit on `develop` whose `build` and `frontend` jobs passed.
2. At most one deploy runs at a time, across Actions and hand deploys. The release script takes `flock` on
   `/opt/trenching-bot/.deploy.lock` and fails fast with a clear message if the lock is held.
3. Rerunning the job for the same commit leaves the server unchanged and succeeds.
4. A release that does not reach `UP` within 180 s is replaced by the previous release, and the job fails. On the first
   deploy, the failed release stays and the job fails.
5. The deploy key can run only the release script, with validated arguments.
6. Negative cases:
   - The server is unreachable: the job fails at SSH and the server is unchanged.
   - `DEPLOY_ENABLED` is unset: the job is skipped.
   - A push during the 03:30 UTC backup: the deploy waits, then deploys.
   - Two pushes in quick succession: they deploy in order.
   - A destructive entity change: see Open Question 1.

## Data Model

No schema change. The schema rule is unchanged: `update` runs on start, and a destructive change needs hand-applied SQL
on the server **before** the push that contains it. Push-to-deploy makes that "before" stricter (Open Question 1).

## Transactions & Integration

- The only transport is SSH from the runner to the server (`ssh -i key deploy@host deploy <sha> < jar`).
- Timeouts:
  - the job: 20 minutes;
  - SSH: `ConnectTimeout=15`, `ServerAliveInterval=15`;
  - the health wait: 180 s;
  - the backup wait: 15 minutes.
- No retries. A failed deploy is visible in GitHub and is retried by rerunning the job.

## What To Implement

1. **The shared release script, test first where testable.**
   - `deploy/trenching-deploy.sh` with the modes, argument validation, lock, idempotency, backup wait and auto-rollback
     above.
   - `deploy.ps1` changes to upload the jar and call it, keeping `-Rollback` and `-SkipTests`, and passing
     `--no-auto-rollback`.
   - Checks: `bash -n` and `shellcheck` (if available) on the script, and the PowerShell parse check on `deploy.ps1`.
2. **The workflow job.** It can be written in parallel with 1; the two touch no common file.
   - A `deploy` job in `.github/workflows/build.yml`, with the trigger, gating, concurrency, environment, Temurin 21,
     Node 24, `assemble -PwithConsole`, the key and known_hosts files written with mode 600, the SSH call, and cleanup in
     an `always()` step.
   - Third-party actions are pinned to the same major versions the workflow already uses. No new actions beyond
     checkout, setup-java, setup-gradle and setup-node.
3. **The runbook.** Depends on 1 and 2. `deploy/README.md` gains a "Push-to-deploy" step:
   - install the script, the `deploy` user, the sudoers drop-in and the forced-command `authorized_keys` line;
   - create the key pair on the PC;
   - add the three secrets and the variable to the `production` environment;
   - check the host fingerprint;
   - do a first manual run;
   - rotate or revoke the key.
   Also update the README's "Server deployment" section, and the Oracle plan's out-of-scope line.

## Verification

- `bash -n deploy/trenching-deploy.sh`, `shellcheck` where available, and the `deploy.ps1` parse check.
- Validate the workflow YAML with `actionlint` if available (otherwise review it). On a push to a non-`develop` branch the
  job is skipped. With `DEPLOY_ENABLED` unset it is skipped on `develop` too, which can be checked as soon as the job is
  merged.
- Once the server exists (runbook):
  - a push deploys and the job shows `Deployed <sha>`;
  - rerunning it shows that the release is already current;
  - an SSH attempt with the deploy key and any other command is refused;
  - a deliberately broken release (for example, one started without `DATABASE_PASSWORD`) is rolled back and the job fails.

## Documentation Sync

- `deploy/README.md`: the new Push-to-deploy step, and the hand deploy now using the installed script.
- `README.md`: the "Server deployment" section notes push-to-deploy.
- The Oracle plan: change "CI/CD deployment … out of scope" to point here.
- CLAUDE.md: one line under Database schema, "with push-to-deploy, apply destructive SQL on the server before pushing to
  `develop`".

## Acceptance Criteria

1. A push to `develop` with green `build` and `frontend` jobs and `DEPLOY_ENABLED=true` ends with the server's
   `current.jar` pointing at `releases/<that sha>.jar` and health `UP`.
2. With `DEPLOY_ENABLED` unset, the job shows as skipped, not failed.
3. A rerun of the same commit does not restart the service.
4. A release that fails health leaves the previous release running, and the job fails.
5. `ssh -i <deploy key> deploy@<host> bash` is refused, and so is any mode other than `deploy`/`rollback`.
6. `deploy.ps1` still deploys and rolls back by hand through the same installed script.

## Open Questions

1. **Destructive entity changes with automatic deploys.** A push carrying a drop, rename, narrowing or retype would
   deploy before anyone applies its SQL. If startup then fails, the job rolls back; if it does not, the change silently
   fails to apply.
   - Recommendation: keep the rule (apply the SQL on the server before pushing) and add one CI guard. The deploy job
     skips itself when the commit message contains `[skip deploy]`, so you can push such a change, apply the SQL, then
     rerun the job.
   - The alternative, a manual approval only for schema changes, is not something GitHub can detect cheaply.
2. **Who gets told about a failed deploy?** GitHub emails the pusher when a workflow fails.
   - Recommendation: rely on that for now. Telegram alerts are a deferred module.
