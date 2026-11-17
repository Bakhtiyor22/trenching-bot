# Project conventions

Rules for working in this repository. Keep this file growing as more come up.

Adapted from `~/.claude/rules/*.md` (written for Java/Lombok/MapStruct) to this
project's actual stack: Kotlin, Spring Boot, a **feature-module** monolith
(see [README.md](README.md#project-layout)), not flat technical layers. Where
a global rule assumed flat layers (`controller/`, `service/`, ...), it applies
here as a sub-package *inside* a module once that module is big enough to need
it (e.g. `telegram/model`, `telegram/repository`), not as a top-level package.

## Environment file privacy

- Agents must never read, inspect, search the contents of, copy, print, quote,
  write or edit `.env`, `.env.*` or `*.env` files, including example/template
  files. Exclude them from content searches, bulk reads, archives and diagnostics.
- Do not expose their contents indirectly through tools, subprocesses, browsers,
  logs or generated artifacts. Never inspect or print secret environment values,
  including `SOLANA_RPC_URL`.
- Never log secrets, tokens, or PII.
- Ask the user to configure needed variables locally. Ask only for variable names
  or whether they are configured, never secret values. Application code may
  consume environment configuration without exposing its contents to the agent.

## Claude Code skills and agents

Project tooling lives in `.claude/`; the developer guide is
[docs/CLAUDE_CODE_GUIDE.md](docs/CLAUDE_CODE_GUIDE.md). Route work like this:

| Situation | Use |
|---|---|
| Writing, changing, reviewing or planning Kotlin code | `kotlin-spring-guidelines` skill (conventions; always loaded by the others) |
| Writing, changing, reviewing or planning anything in `frontend/` | `trading-console-ui` skill (console UI/UX conventions), plus `dataviz` for charts |
| A new feature, design, module contract or schema design | `backplan` skill → plan in `docs/plans/`, stop for approval |
| An approved plan or multi-part task to implement | `orchestrate` skill → `backend-developer` per part, then `backend-reviewer` |
| A scoped implementation part | `backend-developer` agent |
| "Review this" / after an implementation | `review` skill or `backend-reviewer` agent |
| Unclear requirements before starting | `/ask` command (user-invoked only) |

A new feature starts with `backplan`, not code. Small fixes go straight to
implementation, test first.

## Frontend work

- Claude implements frontend changes under `frontend/` itself; Codex no
  longer does them (changed 2026-10-29).
- Do frontend work in its own session, separate from the backend session
  that needs it. Backend parts may define the HTTP contract the frontend
  consumes.
- Verify with the frontend's own test, lint and build commands, and check
  the result in the browser.

## Database schema

- There is no Flyway for now (removed 2026-10-02). The JPA entities are the
  schema: Hibernate runs `ddl-auto: update` in the `local`, `test` and `prod`
  profiles (`prod` is the Oracle server).
- Declare every business unique key and lookup index on the entity, via
  `@Table(uniqueConstraints = [...], indexes = [...])`. Code that relies on a
  duplicate insert failing (e.g. `SimulationRunLedger`) only works if the
  constraint is declared there.
- `update` only adds tables and columns, plus the unique keys and indexes
  declared on entities. It never drops, renames, narrows or retypes anything.
  After such a change, reset the local database with `docker compose down -v`.
  On the server, apply a destructive change (drop, rename, narrow, retype) to
  the database by hand before deploying; never run `docker compose down -v`
  there. With push-to-deploy, apply that SQL on the server *before* pushing
  the change to `develop`, or put `[skip deploy]` in the commit message and
  deploy afterwards with an empty commit.
- [docs/schema/](docs/schema/) keeps the last Flyway SQL for reference. It also
  holds the CHECK constraints, composite foreign keys and the partial unique
  index `uq_positions_open_scenario`, which Hibernate does not create.

## Code style

- Don't duplicate logic. Extract a helper method/function and reuse it instead
  of copy-pasting a variant.
- Keep responsibilities isolated per layer. A thin adapter (a controller, a
  Telegram update handler, etc.) only adapts the transport — parses input,
  calls a service, maps the response. No business logic there. Business logic
  lives in services; persistence details stay behind repositories.
- If a literal value (string, number, path, key) is used more than twice,
  pull it into a constants object instead of repeating it.
- No cross-layer imports downward: a service can use a repository and model,
  never the reverse.

## Kotlin code conventions

JPA entities, DTOs and MapStruct mapping, error handling, logging and naming
rules live in the `kotlin-spring-guidelines` skill (start with its
`references/strict-rules.md`). Load it before writing or reviewing Kotlin code.
