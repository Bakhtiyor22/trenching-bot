# Server deployment runbook

How to run the live instance (automation, captures, console, PostgreSQL) on one Oracle Cloud Always Free Arm VM.
The decisions behind it are in [the plan](../docs/plans/20261115-oracle-cloud-deployment.md).

Rules to keep in mind throughout:

- Only SSH (TCP 22) is reachable from the internet. The app (8081) and PostgreSQL (5432) listen on `127.0.0.1` of the
  server. You reach the console through an SSH tunnel.
- Secrets live in one file on the server, `/etc/trenching-bot/trenching-bot.env`, which you create yourself. Do not
  paste its contents into a chat, an issue or a commit.
- Exactly one instance may run automation, across the PC and the server (step 10).
- PowerShell blocks run on the PC from the project root. Bash blocks run on the server.

What is in this folder:

| File | What it does |
| --- | --- |
| `compose.server.yml` | PostgreSQL 17 in Docker, data on `/data/postgres`, published on `127.0.0.1:5432` only |
| `docker-requires-data.conf` | systemd drop-in that makes Docker wait for the `/data` mount (step 6) |
| `trenching-bot.service` | systemd unit that runs the jar as user `trenching` with `SPRING_PROFILES_ACTIVE=prod` and the (non-secret) `DATABASE_URL` |
| `jvm.options` | JVM memory and timezone settings |
| `deploy.ps1` | Builds from a clean commit, uploads the jar, runs the installed `trenching-deploy` on the server; `-Rollback` |
| `trenching-deploy.sh` | The one copy of the release logic, installed on the server as `/usr/local/bin/trenching-deploy` and run as root (step 7). Used by `deploy.ps1` and by push-to-deploy |
| `trenching-deploy-ssh.sh` | Forced command of the GitHub deploy key, installed as `/usr/local/bin/trenching-deploy-ssh` (step 13). Accepts only `deploy <sha40> <sha256>` and `rollback` |
| `trenching-deploy.sudoers` | sudoers drop-in that lets the `deploy` user run `trenching-deploy` as root and nothing else (step 13) |
| `backup.sh`, `trenching-bot-backup.service`, `trenching-bot-backup.timer` | Nightly `pg_dump` into `/data/backups`, keeping 7 |

## 1. Oracle account (you do this; nobody else can)

1. Sign up at <https://www.oracle.com/cloud/free/>. Choose home region **Germany Central (Frankfurt)**,
   `eu-frankfurt-1`. The home region cannot be changed later, and Always Free resources exist only there.
2. After sign-up, upgrade to **Pay As You Go** (Billing & Cost Management, Upgrade). The A1 allowance stays free, and
   PAYG usually avoids "Out of host capacity" errors.
3. Create a budget: Billing & Cost Management, Budgets, Create. Monthly budget of 1 USD, with an alert rule on actual spend
   that emails you. This warns you the first time anything is billed.

## 2. SSH key and host alias (PC)

```powershell
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\id_ed25519_oci" -C "trenching-oci"
Get-Content "$env:USERPROFILE\.ssh\id_ed25519_oci.pub"
```

Use a passphrase. Copy the one-line public key for step 4. Never share the file without `.pub`.

Add the alias after the VM has its public IP (step 4):

```powershell
notepad "$env:USERPROFILE\.ssh\config"
```

```
Host trenching-oci
    HostName <the VM's public IP>
    User ubuntu
    IdentityFile ~/.ssh/id_ed25519_oci
    ServerAliveInterval 30
```

All later commands use the alias `trenching-oci`.

## 3. Network (OCI console)

1. Networking, Virtual cloud networks, **Start VCN Wizard**, "Create VCN with Internet Connectivity". Name it
   `trenching-vcn` and accept the defaults.
2. Open the VCN, then the **public** subnet, then its **Default Security List**. In Ingress Rules keep **only** the rule for
   TCP port 22 from `0.0.0.0/0`. Delete every other TCP/UDP rule. Keep the two ICMP "destination unreachable" rules: they
   open no port, and without them large transfers such as the multi-GB capture upload can stall on MTU problems.
3. Do not add a rule for 8081 or 5432, now or later. Your home IP is not fixed, so 22 stays open to all; the protection
   is key-only login (step 6) and fail2ban.

## 4. Instance (OCI console)

Compute, Instances, Create instance:

- Name `trenching-bot`. Placement: any availability domain in Frankfurt.
- Image: **Canonical Ubuntu 24.04** (the aarch64 build). Shape: Ampere, **VM.Standard.A1.Flex**, **2 OCPU, 12 GB**.
- Networking: the VCN and public subnet from step 3, **assign a public IPv4 address**.
- Add SSH keys: paste the public key from step 2.
- Boot volume: **50 GB**.

If you get "Out of host capacity", retry in a few minutes, pick another availability domain, or retry later in the day.
Then finish the SSH alias from step 2 and test:

```powershell
ssh trenching-oci "uname -m && lsb_release -ds"
```

It should print `aarch64` and Ubuntu 24.04.

## 5. Block volume for `/data` (OCI console, then server)

1. Storage, Block Volumes, Create: name `trenching-data`, **100 GB**, same availability domain as the instance.
2. Open the instance, Attached block volumes, Attach: type **Paravirtualized**, access Read/Write. Wait until it is attached.
3. On the server, find the new empty disk. It is the one with no partitions and no mount point, about 100G:

```bash
lsblk -o NAME,SIZE,TYPE,MOUNTPOINT
ls -l /dev/oracleoci/
```

Use the plain device name `lsblk` shows for that disk (usually `/dev/sdb`), not the `/dev/oracleoci/` names: their
numbering does not reliably match (on the first server `oraclevda` pointed at the new volume). The steps below use
`/dev/sdb`; replace it if `lsblk` showed another name. Check that the disk has no filesystem before you format it:
`mkfs` erases whatever is there.

```bash
sudo blkid /dev/sdb || echo "EMPTY - safe to format"
```

Only if it prints `EMPTY - safe to format`, format it and mount it by UUID, so the device name does not matter after a
reboot. `nofail` lets the VM boot even if the volume is missing:

```bash
sudo mkfs.ext4 -L trenching-data /dev/sdb
sudo mkdir -p /data
UUID=$(sudo blkid -s UUID -o value /dev/sdb); echo "UUID=$UUID"
echo "UUID=$UUID /data ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab
sudo systemctl daemon-reload
sudo mount -a
df -h /data
```

`df` must show about 98G on `/data` (ext4 keeps a little of the 100G for itself).

## 6. Server bootstrap (server)

Time, updates, journal:

```bash
sudo timedatectl set-timezone UTC
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get install -y unattended-upgrades fail2ban ca-certificates curl gpg
sudo mkdir -p /var/log/journal
sudo systemctl enable --now fail2ban
```

Docker Engine and the Compose plugin (official arm64 repository):

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker
```

Make Docker wait for the `/data` mount. The fstab entry has `nofail`, so without this Docker can start before `/data` is
mounted on a reboot; the PostgreSQL container then fails (it must not create the folder) and is not retried:

From the PC, copy the drop-in:

```powershell
scp .\deploy\docker-requires-data.conf trenching-oci:~/
```

Then on the server:

```bash
sudo install -d -m 0755 /etc/systemd/system/docker.service.d
sudo install -m 0644 ~/docker-requires-data.conf /etc/systemd/system/docker.service.d/requires-data.conf
sudo systemctl daemon-reload
systemctl show docker -p RequiresMountsFor
```

The last line must print `RequiresMountsFor=/data`.

Temurin 21 JRE (Adoptium repository):

```bash
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" | sudo tee /etc/apt/sources.list.d/adoptium.list > /dev/null
sudo apt-get update
sudo apt-get install -y temurin-21-jre
/usr/bin/java -version
```

SSH hardening. The file name starts with `00` because sshd keeps the first value it reads, and the cloud image already ships
a drop-in. Keep your current session open and test a second login before closing it:

```bash
printf 'PasswordAuthentication no\nPermitRootLogin no\n' | sudo tee /etc/ssh/sshd_config.d/00-hardening.conf
sudo sshd -t && sudo systemctl reload ssh
```

Firewall: Oracle's Ubuntu images ship iptables rules that accept only SSH and reject the rest. Keep them. Do not open 8081
or 5432 there, and do not install `ufw` on top of them.

The service user and the folders:

```bash
sudo useradd --system --home-dir /opt/trenching-bot --shell /usr/sbin/nologin trenching
sudo install -d -m 0755 -o root -g root /opt/trenching-bot /opt/trenching-bot/releases
sudo install -d -m 0750 -o trenching -g trenching /data/captures /data/logs
sudo install -d -m 0700 -o root -g root /data/backups
sudo install -d -m 0700 -o 70 -g 70 /data/postgres
sudo install -d -m 0750 -o root -g trenching /etc/trenching-bot
```

`70` is the `postgres` user inside the `postgres:17-alpine` image.

## 7. Secrets file, deploy files, PostgreSQL (server)

Create the file empty, with the right owner and mode, then edit it yourself:

```bash
sudo install -m 600 -o root -g root /dev/null /etc/trenching-bot/trenching-bot.env
sudo nano /etc/trenching-bot/trenching-bot.env
```

Write one `NAME=value` per line, no quotes and no spaces around `=`:

- `SOLANA_RPC_URL` is your Helius RPC URL, copied from the PC's own configuration.
- `DATABASE_USERNAME` is a new name, for example `trenching_app`. Do not reuse the committed `trenching`.
- `DATABASE_PASSWORD` is a new password. Generate one with `openssl rand -base64 32` and paste it unchanged: it must be
  that base64 output and nothing else (no `$`, no quotes), because Compose interpolates `$` in `--env-file` values while
  systemd's `EnvironmentFile` does not, so the database and the app would end up with different passwords. Do not reuse
  `trenching`.

The file holds only these three secrets (and later `TRENCHING_AUTOMATION_ENABLED`). The profile (`prod`) and
`DATABASE_URL` are not secret and are set in `trenching-bot.service`.

Never put `SPRING_PROFILES_ACTIVE` or `DATABASE_URL` in this file: systemd's `EnvironmentFile=` overrides `Environment=`, so a stray line here would silently win over the unit.

Do not add `TRENCHING_AUTOMATION_ENABLED` yet. That is step 10.

Copy the deploy files from the PC and install them:

```powershell
scp -r .\deploy trenching-oci:~/
```

```bash
cd ~
sudo install -m 0644 deploy/trenching-bot.service deploy/trenching-bot-backup.service deploy/trenching-bot-backup.timer /etc/systemd/system/
sudo install -m 0644 -o root -g root deploy/jvm.options /opt/trenching-bot/jvm.options
sudo install -m 0755 -o root -g root deploy/backup.sh /opt/trenching-bot/backup.sh
sudo systemctl daemon-reload
sudo systemctl enable trenching-bot
```

Install the release script. `deploy.ps1` carries no release logic of its own: it uploads the jar and calls this installed
script, and fails with a pointer to this step if the script is missing. Do this before the first deploy (step 9):

```bash
cd ~
sudo install -m 0755 -o root -g root deploy/trenching-deploy.sh /usr/local/bin/trenching-deploy
```

When `deploy/trenching-deploy.sh` changes in the repository, copy it to the server and run the same `install` line again
by hand. The push-to-deploy key deliberately cannot update it (step 13).

Start PostgreSQL. Compose reads the env file only to fill in the database name and password:

```bash
cd ~
sudo docker compose --env-file /etc/trenching-bot/trenching-bot.env -f deploy/compose.server.yml up -d
sudo docker compose --env-file /etc/trenching-bot/trenching-bot.env -f deploy/compose.server.yml ps
```

Wait until the container is `healthy`. The database `trenching_bot` is now empty. Do not start the app yet.

`POSTGRES_PASSWORD` only takes effect when PostgreSQL first initialises `/data/postgres`. To rotate `DATABASE_PASSWORD`
later, first run `ALTER ROLE <user> PASSWORD '<new>'` in the database (`docker exec -i trenching-bot-postgres psql -U
<user> -d trenching_bot`), then change the env file and restart the app. Changing the file alone does not change the
database's password.

## 8. Migrate the database and the captures

Do this in order. The restore must happen before the app's first start, otherwise Hibernate creates the tables first and
the restore collides with them.

### 8.1 Stop the PC instance (PC)

Stop `bootRun` or the IDE run configuration, and confirm nothing listens on 8081:

```powershell
Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue
```

It must print nothing. Keep the Docker database container running; you need it for the dump.

Do not start the PC app again until step 10.1 has set `enabled: false`: from now on the PC database is a stale copy, and
a live PC instance would spend the request cap and capture the same buys as the server.

Do **not** run `gradlew clean` until step 8.3 is done: it deletes `build/captures`, the only copy of the capture evidence.

### 8.2 Dump and pack (PC)

Work in a folder outside the repository. `pg_dump -Fc` writes a binary file, so dump inside the container and copy it out;
PowerShell's `>` would corrupt it.

```powershell
$m = "$env:USERPROFILE\trenching-migration"
New-Item -ItemType Directory -Force $m | Out-Null
docker exec trenching-bot-postgres pg_dump -U trenching -d trenching_bot -Fc -f /tmp/trenching_bot.dump
docker cp trenching-bot-postgres:/tmp/trenching_bot.dump "$m\trenching_bot.dump"
docker exec trenching-bot-postgres rm /tmp/trenching_bot.dump
tar -czf "$m\captures.tar.gz" -C build captures
Get-ChildItem $m
```

Record the row counts on the PC now. The same query runs on both sides:

```powershell
$q = "SELECT table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text::bigint AS row_count FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name;"
docker exec trenching-bot-postgres psql -U trenching -d trenching_bot -At -F ',' -c $q | Out-File -Encoding ascii "$m\counts-pc.txt"
Get-Content "$m\counts-pc.txt"
```

### 8.3 Upload (PC)

```powershell
scp "$m\trenching_bot.dump" "$m\captures.tar.gz" trenching-oci:~/
```

The capture archive is several GB (the captures were 7.5 GB in 461 sessions on 2026-11-17); allow time. If it breaks,
run the same command again.

### 8.4 Restore and unpack (server)

```bash
cd ~
DB_USER="$(sudo grep '^DATABASE_USERNAME=' /etc/trenching-bot/trenching-bot.env | cut -d= -f2-)"
sudo docker exec -i trenching-bot-postgres pg_restore -U "$DB_USER" -d trenching_bot --no-owner --no-acl --role="$DB_USER" < trenching_bot.dump
sudo tar -xzf captures.tar.gz -C /data
sudo chown -R trenching:trenching /data/captures
ls /data/captures | head
```

`pg_restore` should print nothing. A message about a missing role or owner means the flags were left out; a message about
an object that already exists means the app already ran, so stop and ask before going on.

### 8.5 Compare row counts (server, then PC)

```bash
cd ~
DB_USER="$(sudo grep '^DATABASE_USERNAME=' /etc/trenching-bot/trenching-bot.env | cut -d= -f2-)"
Q="SELECT table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text::bigint AS row_count FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name;"
sudo docker exec trenching-bot-postgres psql -U "$DB_USER" -d trenching_bot -At -F , -c "$Q" > counts-server.txt
cat counts-server.txt
```

```powershell
scp trenching-oci:~/counts-server.txt "$m\counts-server.txt"
Compare-Object (Get-Content "$m\counts-pc.txt") (Get-Content "$m\counts-server.txt")
```

`Compare-Object` must print nothing. Check at least `replay_datasets`, `simulation_runs`, `positions` and the
automation tables. Count the capture sessions too: the server count must match the PC's
(`(Get-ChildItem build\captures\console -Directory).Count`; 461 on 2026-11-17).

```bash
ls /data/captures/console | wc -l
```

Once the counts match, delete the dump and archive from the server (`rm ~/trenching_bot.dump ~/captures.tar.gz`). Keep the
PC copies until step 11 has produced a restorable backup.

## 9. First deploy, automation still off (PC)

`deploy.ps1` builds from a clean commit, so the commit that holds the `prod` profile changes (`application-prod.yml`) must exist and your working
tree must be clean. `JAVA_HOME` must point at a JDK 21.

Stop the Vite dev server (`npm run dev`) and any `vitest` watcher first. The `-PwithConsole` build runs `npm ci`, which
deletes `frontend/node_modules` before reinstalling it. On Windows a running dev server locks a native file in there, so
`npm ci` fails partway and leaves most of `node_modules` deleted. If that happens, stop the dev server and run
`npm --prefix frontend ci` again.

```powershell
.\deploy\deploy.ps1 -Server trenching-oci
```

It runs `gradlew build -PwithConsole` (use `-SkipTests` only for a commit that already passed `build`), copies the jar to
`/tmp` on the server with `scp`, then runs the installed script
(`sudo trenching-deploy deploy <sha> <sha256> --no-auto-rollback --file /tmp/...`). The script:

- names the release by the full 40-character commit SHA and installs `/opt/trenching-bot/releases/<sha40>.jar`, after
  checking the jar's SHA-256 and zip header;
- takes a lock, so two deploys cannot overlap, and fails at once if the lock is held;
- does nothing if that release is already current and healthy, so a rerun is safe;
- waits while the nightly backup (03:30 UTC) is running, up to 15 minutes, before it restarts the service;
- restarts the service and waits up to 180 seconds for `/actuator/health` to report `UP`.

A hand deploy passes `--no-auto-rollback`: on failure it prints the last 80 journal lines and stops for you to look, and
tells you to run `-Rollback`.

Check the server:

```bash
systemctl is-active trenching-bot
sudo ss -tulnp
```

Confirm that every listener that is not on a loopback address (`127.0.0.1`, `[::1]`, `127.0.0.53`) is on port 22, and that
`8081` and `5432` show `127.0.0.1`. From the PC, both of these must
time out:

```powershell
Test-NetConnection <public IP> -Port 8081
Test-NetConnection <public IP> -Port 5432
```

Open the console through a tunnel. Leave this window running:

```powershell
ssh -N -L 8081:127.0.0.1:8081 trenching-oci
```

Then open <http://localhost:8081/console/>. The tunnel works with the console's local-only (loopback) check because the request reaches
the server from the loopback address with `Host: localhost:8081`. The PC app must not be running (step 8.1), which also frees port 8081; or use
another local port and a matching URL: `-L 18081:127.0.0.1:8081` and `http://localhost:18081/console/`.

Check that `/actuator/health` is `UP` and that the capture list loads (HTTP 200, not `LOCAL_ACCESS_REQUIRED`). Automation
is off: the console shows it disabled.

## 10. Cut-over: one automation instance only

Two instances with automation on would each spend the Helius request cap (it is counted per database, and they now hold
separate databases) and would capture the same buys twice. Make the PC safe **first**:

1. On the PC, set `enabled: false` under `trenching.automation` in the untracked `config/application-local.yml`. If you
   start the PC another way, set `TRENCHING_AUTOMATION_ENABLED=false` for that run. Any start that does not override the
   profile is otherwise a live local instance (architecture review F6).
2. On the server, turn automation on:

```bash
sudo nano /etc/trenching-bot/trenching-bot.env
```

Add the line `TRENCHING_AUTOMATION_ENABLED=true`, then:

```bash
sudo systemctl restart trenching-bot
journalctl -u trenching-bot -n 50 --no-pager
```

In the console through the tunnel, the automation status should now show discovery ticks. Switch the PC off or leave it
running: it no longer discovers anything. To go back, remove the line (or set it to `false`) and restart the service.

## 11. Backups

On the server, enable the nightly dump (03:30 UTC) and take the first one by hand:

```bash
sudo systemctl enable --now trenching-bot-backup.timer
sudo systemctl start trenching-bot-backup.service
sudo ls -lh /data/backups
systemctl list-timers trenching-bot-backup.timer
```

`backup.sh` keeps the 7 newest dumps. It checks free space first (the larger of 5 GB and twice the newest dump) and fails
with a non-zero exit if there is not enough room. It never deletes older dumps to make room. A failed run shows in
`systemctl status trenching-bot-backup.service`. If you see it, free space on `/data` yourself.

**Volume backup (OCI console).** Block Volumes, Backup Policies, Create: a weekly schedule, retention 4 weeks, incremental.
Assign it to `trenching-data` (Block Volumes, the volume, Backup Policy, Assign). Four kept backups plus one being created
fit the 5 free ones.

**Weekly copy to the PC.** Dumps are root-only, so copy through a temporary file (PC):

```powershell
$f = (ssh trenching-oci "sudo ls -1t /data/backups | grep '^trenching_bot-.*\.dump$' | head -n 1")
ssh trenching-oci "sudo cp /data/backups/$f ~/$f && sudo chown ubuntu ~/$f"
scp "trenching-oci:~/$f" "$env:USERPROFILE\trenching-migration\$f"
ssh trenching-oci "rm ~/$f"
```

**Restore drill.** Do it once now, and again after any big change. It restores the newest dump into a scratch database,
compares counts with the live one, and drops it (server):

```bash
cd ~
DB_USER="$(sudo grep '^DATABASE_USERNAME=' /etc/trenching-bot/trenching-bot.env | cut -d= -f2-)"
LATEST="$(sudo ls -1t /data/backups | grep '^trenching_bot-.*\.dump$' | head -n 1)"
sudo docker exec trenching-bot-postgres createdb -U "$DB_USER" trenching_bot_restore_check
sudo cat "/data/backups/$LATEST" | sudo docker exec -i trenching-bot-postgres pg_restore -U "$DB_USER" -d trenching_bot_restore_check --no-owner --no-acl --role="$DB_USER"
Q="SELECT table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text::bigint AS row_count FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name;"
sudo docker exec trenching-bot-postgres psql -U "$DB_USER" -d trenching_bot_restore_check -At -F , -c "$Q"
sudo docker exec trenching-bot-postgres psql -U "$DB_USER" -d trenching_bot -At -F , -c "$Q"
sudo docker exec trenching-bot-postgres dropdb -U "$DB_USER" trenching_bot_restore_check
```

The two lists must match, apart from tables the live app wrote to since the dump (automation tables grow). The restored
copy must have the same tables and the counts of the static ones (`replay_datasets`, `simulation_runs`).

## 12. Day-2

**Deploy** by hand (PC, clean commit, Vite dev server stopped, see step 9). With push-to-deploy on (step 13) a push to
`develop` does this for you; the hand deploy stays available and uses the same installed script and release names:

```powershell
.\deploy\deploy.ps1 -Server trenching-oci
```

**Roll back** to the previous release (also use it when a deploy reports a failed health check):

```powershell
.\deploy\deploy.ps1 -Server trenching-oci -Rollback
```

A rollback takes the same lock and also waits for a running backup, so during the 03:30 UTC backup it can wait up to
15 minutes before it restarts the service.

The script keeps the 5 newest jars in `/opt/trenching-bot/releases`, and records the previous one in
`/opt/trenching-bot/previous`. Running `-Rollback` twice switches back to the newer release.
After two failed deploys in a row, `previous` points at the first bad release, so `-Rollback` would not reach a good one.
Pick it by hand: `ls -1t /opt/trenching-bot/releases`, then
`sudo ln -sfn /opt/trenching-bot/releases/<good sha>.jar /opt/trenching-bot/current.jar` and
`sudo systemctl restart trenching-bot`.

**Logs** (server):

```bash
journalctl -u trenching-bot -f
journalctl -u trenching-bot -n 200 --no-pager
sudo tail -n 100 /data/logs/trenching-bot.log
```

The log folder is readable only by the `trenching` user and root, hence `sudo`.

**Deploy timing and schema errors.** Avoid deploying or restarting around the 03:30 UTC backup: `pg_dump`'s locks can delay the schema update on start. After the first deploy, check the log for Hibernate DDL errors (`journalctl -u trenching-bot -n 500 --no-pager | grep -i -E 'ddl|schema'`): `update` logs a failed unique key or index and carries on instead of stopping.

**Reboot check.** After kernel updates, or whenever you want proof that recovery works:

```bash
sudo reboot
```

Wait a minute, log in again and run `systemctl is-active trenching-bot` and `sudo docker ps`. PostgreSQL and the app must
be back with no action from you. If PostgreSQL is not, check that the drop-in from step 6 is installed
(`systemctl show docker -p RequiresMountsFor` must print `/data`) and that `df -h /data` shows the volume.

**Schema.** There is no Flyway. The `prod` profile runs `ddl-auto: update`: on start, Hibernate adds new tables, columns,
unique keys and indexes from the entities, and the schema-drift repair runs as on the PC. It never drops, renames, narrows
or retypes anything. For such a destructive change (drop, rename, narrow, retype), apply the SQL to the server
database by hand **before** deploying (see [CLAUDE.md](../CLAUDE.md#database-schema)). **Never run `docker compose down -v`
or delete `/data/postgres` on the server.**

**Idle reclamation.** Oracle may reclaim an idle Always Free instance when CPU, network and memory all stay under 20 % for
7 days. `jvm.options` pins a 3 GB starting heap with `AlwaysPreTouch` so memory stays above the threshold. Check in the OCI
console, Compute, the instance, Metrics, memory utilisation, after the first week. If it sits below 20 %, ask before
raising `-Xms`.

**Never** run `npm run serve:public` (the public jprq/ngrok tunnel) on the server, and never open 8081 or 5432 in the OCI
security list or the host firewall. The console has no login, and the local-only guard can be bypassed (architecture review
[F5](../docs/architecture/20261112-architecture-review.md)). F5 must be fixed before any access other than the SSH tunnel:
Tailscale, LAN or public.

## 13. Push-to-deploy (GitHub Actions)

Do this only after a hand deploy has worked (step 9) and `trenching-deploy` is installed (step 7). After it, every push to
`develop` whose `build` and `frontend` jobs pass deploys by itself. The decisions are in
[the plan](../docs/plans/20261116-github-actions-deploy.md).

How it works:

- The `deploy` job builds the jar with the console, then runs `ssh deploy@<ip> "deploy <sha40> <sha256>" < jar`. The
  release name is the full commit SHA, so a push deploy and a hand deploy of the same commit are the same release.
- The `deploy` user cannot open a shell. Its key is forced to run `trenching-deploy-ssh`, which accepts only
  `deploy <40 hex> <64 hex>` and `rollback`, then runs the root release script through `sudo`. A leaked key can deploy a
  jar or roll back, nothing else.
- Port 22 stays open to all, as before. Nothing new is opened.

### 13.1 The deploy user (server)

If the server's `~/deploy` folder is older than the repository, copy it again from the PC:

```powershell
scp -r .\deploy trenching-oci:~/
```

```bash
cd ~
sudo adduser --disabled-password --gecos "" deploy
sudo install -m 0755 -o root -g root deploy/trenching-deploy-ssh.sh /usr/local/bin/trenching-deploy-ssh
sudo install -m 0440 -o root -g root deploy/trenching-deploy.sudoers /etc/sudoers.d/trenching-deploy
sudo visudo -cf /etc/sudoers.d/trenching-deploy
```

`sudo useradd -m -s /bin/bash deploy` works instead of `adduser`. `visudo` must print `parsed OK`. The sudoers file
allows only `/usr/local/bin/trenching-deploy`. The user has no password, and `PasswordAuthentication no` (step 6) already
blocks password logins.

### 13.2 A key only for GitHub (PC)

```powershell
ssh-keygen -t ed25519 -f $HOME\.ssh\trenching-deploy-github -N '""' -C github-deploy
Get-Content $HOME\.ssh\trenching-deploy-github.pub
```

The key has no passphrase because the GitHub runner uses it unattended. `-N '""'` is how Windows PowerShell 5.1 passes an
empty passphrase to `ssh-keygen`; a plain `-N ""` is dropped and the command would prompt. Use this key only for GitHub,
never as your own login key, and do not copy the private file anywhere except the GitHub secret in 13.5.

### 13.3 Authorize the key (server)

Create the key file with the right owner and mode, then put exactly one line in it. Replace `AAAA...` with the public key
from 13.2 (the part after `ssh-ed25519`):

```bash
sudo install -d -m 0700 -o deploy -g deploy /home/deploy/.ssh
sudo install -m 0600 -o deploy -g deploy /dev/null /home/deploy/.ssh/authorized_keys
sudo nano /home/deploy/.ssh/authorized_keys
```

```
restrict,command="/usr/local/bin/trenching-deploy-ssh" ssh-ed25519 AAAA... github-deploy
```

- `restrict` turns off a terminal, port forwarding, agent forwarding and X11 for this key.
- `command=` makes sshd run the wrapper whatever the client asks for. The wrapper only reads the client's request from
  `SSH_ORIGINAL_COMMAND`, and refuses anything that is not `deploy <sha40> <sha256>` or `rollback`.
- The wrapper never passes `--file` or `--no-auto-rollback`, so a push deploy always rolls back by itself on failure and
  can only install the jar sent on stdin. The key also cannot update the release script (step 7).

### 13.4 Host fingerprint (server, then PC)

The job pins the server's host key instead of trusting whatever answers. On the server:

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

On the PC, scan and compare:

```powershell
$scan = ssh-keyscan -t ed25519 <public IP>
$scan
$scan | ssh-keygen -lf -
```

The `SHA256:...` fingerprint from the last command must equal the server's. If it does not, stop and do not use the scan.
The scan line (`<ip> ssh-ed25519 AAAA...`) is the value of `DEPLOY_KNOWN_HOSTS`.

### 13.5 GitHub settings (you, in the web UI)

Nobody else can do this, and no agent should see these values.

1. Settings, Environments, New environment `production`. Deployment branches and tags: Selected branches, add `develop`.
   Leave "Required reviewers" off: deploys are automatic by choice.
2. In `production`, add Environment secrets:
   - `DEPLOY_SSH_KEY`: the whole contents of `$HOME\.ssh\trenching-deploy-github` (the private file, with its BEGIN and
     END lines);
   - `DEPLOY_HOST`: the server's public IP;
   - `DEPLOY_KNOWN_HOSTS`: the scan line from 13.4.
3. Settings, Secrets and variables, Actions, Variables, New repository variable: `DEPLOY_ENABLED` = `true`. It is a
   repository variable because a job's `if:` cannot read environment variables. Until it is set to exactly `true`, the
   `deploy` job shows as skipped.

### 13.6 Check it

From the PC, the key must be refused for anything but the two commands. Both of these print
`Refused: only 'deploy <sha40> <sha256>' and 'rollback' are allowed.` and exit with code 2 (check `$LASTEXITCODE`):

```powershell
ssh -i $HOME\.ssh\trenching-deploy-github -o IdentitiesOnly=yes deploy@<public IP>
ssh -i $HOME\.ssh\trenching-deploy-github -o IdentitiesOnly=yes deploy@<public IP> bash
```

Then push a commit to `develop`. In GitHub, Actions, the run, the `deploy` job: the "Deploy over SSH" step ends with
`Deployed <sha40>.` Re-run the job from the same page: it ends with `Release <sha40> is already current.` and the service
is not restarted. The job log is also where a failed deploy shows its journal tail. On the server,
`readlink /opt/trenching-bot/current.jar` shows the new release.

### 13.7 When it fails, and when to hold it back

- **The release does not become healthy within 180 s.** The script puts the previous release back, restarts, waits for
  health, prints the journal tail and exits 1. The job fails and GitHub emails the pusher; the server keeps running the
  previous release. On the very first deploy there is no previous release, so the failed release stays and the job just
  fails: read the journal tail, fix, and push again.
- **The server cannot be reached, or another deploy holds the lock.** The job fails and the server is unchanged. Re-run
  the job.
- **A destructive entity change** (drop, rename, narrow, retype; see **Schema** in step 12) must not deploy before its SQL
  is applied. Put `[skip deploy]` in that commit's message and push: the job is skipped. Apply the SQL on the server, then
  push an empty commit to deploy:

```powershell
git commit --allow-empty -m "deploy"
git push
```

Re-running the skipped job stays skipped, because its commit message still contains `[skip deploy]`; that is why you push
a new commit. Without `[skip deploy]`, apply the SQL before you push.

### 13.8 Turn it off, rotate the key

- Pause: set `DEPLOY_ENABLED` to anything other than `true`, or delete it. Hard stop: delete the key line from
  `/home/deploy/.ssh/authorized_keys` on the server.
- Rotate: make a new key pair (13.2), replace the line in `authorized_keys` (13.3) and the `DEPLOY_SSH_KEY` secret (13.5),
  run a deploy to check it, then delete the old private key file.
- If the host key changes (server rebuilt), repeat 13.4 and replace `DEPLOY_KNOWN_HOSTS`.

The earlier warnings still apply: exactly one instance runs automation (step 10), and schema changes follow the rule in
step 12. A push deploy builds on the GitHub runner, so the Vite warning in step 9 matters only for hand deploys.
