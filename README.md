# trenching-bot

Solana paper-trading research system.

This repository currently contains **only the technical foundation**: build setup,
configuration, local infrastructure, a health check and a minimal Telegram
alert sender. No trading logic or Solana integration is implemented yet.

## Stack

| Concern        | Choice                        |
| -------------- | ----------------------------- |
| Language       | Kotlin 2.3                    |
| Runtime        | Java 21                       |
| Framework      | Spring Boot 4.1               |
| Build          | Gradle (Kotlin DSL) + wrapper |
| Database       | PostgreSQL 17                 |
| Migrations     | Flyway                        |
| Tests          | JUnit 5 + Testcontainers      |

## Requirements

- JDK 21 (the Gradle toolchain resolves it; `JAVA_HOME` must point at a JDK)
- Docker with Compose v2 (for local PostgreSQL and for Testcontainers)

## Project layout

The application is a **modular monolith**: one deployable, with module boundaries
expressed as packages under `com.trenching.bot`. Modules are empty placeholders
until the corresponding features are built.

```
src/main/kotlin/com/trenching/bot
├── TrenchingBotApplication.kt   Spring Boot entry point
├── common/                      shared kernel (types used across modules)
├── config/                      application-wide Spring configuration
├── marketdata/                  (empty) Solana market data ingestion
├── strategy/                    (empty) signal generation
├── papertrading/                (empty) simulated execution and portfolio
├── notification/                (empty) outbound notifications
└── telegram/                    Telegram alert sender (TelegramAlertSender)
```

Rules of thumb for the boundaries:

- A module owns its own persistence and exposes a narrow Kotlin API to others.
- Modules do not reach into each other's internals; shared types live in `common`.
- Database migrations live in `src/main/resources/db/migration`, named with a UTC
  timestamp (`V<yyyyMMddHHmmss>__<description>.sql`) — see [CLAUDE.md](CLAUDE.md).

## Working with Claude Code

The repository ships shared Claude Code skills, agents and permissions in `.claude/`.
See [docs/CLAUDE_CODE_GUIDE.md](docs/CLAUDE_CODE_GUIDE.md) for setup and for which skill
triggers in which scenario.

## Running locally

### 1. Start PostgreSQL

```bash
docker compose up -d
```

Defaults (override via environment variables or a `.env` file):

| Variable            | Default        |
| ------------------- | -------------- |
| `POSTGRES_DB`       | `trenching_bot`|
| `POSTGRES_USER`     | `trenching`    |
| `POSTGRES_PASSWORD` | `trenching`    |
| `POSTGRES_PORT`     | `5432`         |

Stop it with `docker compose down`, or `docker compose down -v` to also drop the
data volume.

### 2. Run the application

```bash
./gradlew bootRun
```

`bootRun` activates the `local` profile, which points at the Compose database.
The application listens on <http://localhost:8080>.

### 3. Check health

```bash
curl http://localhost:8080/actuator/health
```

Returns `{"status":"UP", ...}` once the application and its database connection
are ready. Under the `local` profile the response includes per-indicator details
(database, disk space, ping).

## Build

```bash
./gradlew build
```

Compiles, runs the tests and produces `build/libs/trenching-bot-0.0.1-SNAPSHOT.jar`.
The jar needs a profile, since there is no default one:

```bash
SPRING_PROFILES_ACTIVE=local java -jar build/libs/trenching-bot-0.0.1-SNAPSHOT.jar
```

To compile without running tests:

```bash
./gradlew assemble
```

## Tests

```bash
./gradlew test
```

Integration tests start a disposable PostgreSQL container through Testcontainers,
so **Docker must be running**. They do not use the Compose database and leave no
state behind.

## Configuration

`application.yml` holds only what is true in every environment: application name,
server port and actuator exposure. Everything that varies — datasource and
JPA/Hibernate behaviour — lives in the profile file, so no profile can silently
inherit another environment's database.

| File                    | Profile | Purpose                                                           |
| ----------------------- | ------- | ----------------------------------------------------------------- |
| `application.yml`       | —       | App name, server port, actuator exposure                          |
| `application-local.yml` | `local` | Compose database; verbose logging, health details                 |
| `application-prod.yml`  | `prod`  | Datasource from the environment; quiet logging, no health details |

There is **no default profile** — one is always chosen explicitly through
`SPRING_PROFILES_ACTIVE=<profile>`. `bootRun` sets `local` for you.

Flyway is not configured at all: it runs on Spring Boot's defaults, which are
already what this project needs (enabled, reading `classpath:db/migration`).

### Telegram alerts

The `telegram` module long-polls Telegram's `getUpdates` for the configured
bot and records every chat that messages it in `telegram_chats`, with
`allowed = false` by default. `TelegramAlertSender.sendAlert` broadcasts to
every chat where `allowed = true`, via the Telegram Bot HTTP API.

There is no admin flow yet: after messaging your bot once, promote your own
chat manually:

```sql
update telegram_chats set allowed = true where chat_id = <your chat id>;
```

Both the listener and `sendAlert` are optional in every profile: with no bot
token configured, the listener doesn't start and `sendAlert` logs a warning
and does nothing instead of failing. With a token but no allowed chats yet,
`sendAlert` also just logs and skips.

| Variable            | Required | Meaning                    |
| -------------------- | -------- | --------------------------- |
| `TELEGRAM_BOT_TOKEN` | no       | Bot token from @BotFather   |

### Running with the `prod` profile

The `prod` datasource has **no defaults** — the application fails to start if a
variable is missing, rather than falling back to a wrong database.

```bash
SPRING_PROFILES_ACTIVE=prod \
DATABASE_URL=jdbc:postgresql://<host>:5432/trenching_bot \
DATABASE_USERNAME=<user> \
DATABASE_PASSWORD=<password> \
java -jar build/libs/trenching-bot-0.0.1-SNAPSHOT.jar
```

| Variable            | Required | Meaning                                  |
| ------------------- | -------- | ---------------------------------------- |
| `DATABASE_URL`      | yes      | JDBC URL                                 |
| `DATABASE_USERNAME` | yes      | Database user                            |
| `DATABASE_PASSWORD` | yes      | Database password                        |
| `DATABASE_POOL_SIZE`| no       | Hikari maximum pool size (default `10`)  |
| `SERVER_PORT`       | no       | HTTP port (default `8080`)               |
