# Working with Claude Code in trenching-bot

This repository ships a shared Claude Code setup in `.claude/`: project conventions as a skill,
workflow skills for planning / orchestrating / reviewing, two sub-agents, one slash command, and a
permission policy. This guide explains what each piece does, **when it kicks in on its own**, and
how to call it explicitly.

## 1. One-time setup per developer

1. Install Claude Code and open it in the repository root.
2. Make sure a JDK 21 and Docker are available (`./gradlew test` needs Docker for Testcontainers).
3. If `java` is not on your `PATH`, create **`.claude/settings.local.json`** (gitignored, personal)
   pointing at your JDK:

   ```json
   {
     "env": {
       "JAVA_HOME": "C:\\Users\\<you>\\.jdks\\ms-21.0.12"
     }
   }
   ```

   On macOS/Linux use your JDK path, e.g. `/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`.
4. Start a new Claude Code session so the settings load. Type `/` to see the project skills
   (`/backplan`, `/orchestrate`, `/review`, `/kotlin-spring-guidelines`) and the `/ask` command.

## 2. What's in `.claude/`

```
.claude/
├── settings.json                  shared permission policy (committed)
├── settings.local.json            your personal overrides, e.g. JAVA_HOME (gitignored)
├── skills/
│   ├── kotlin-spring-guidelines/  project conventions + references/ (strict rules, domain, ...)
│   ├── backplan/                  write a plan for a feature
│   ├── orchestrate/               split an approved plan across sub-agents
│   └── review/                    review a change against its requirement
├── agents/
│   ├── backend-developer.md       implements a scoped part, test-first
│   └── backend-reviewer.md        reviews a change using the review skill
└── commands/
    └── ask.md                     /ask — list open questions and stop
```

`CLAUDE.md` (repo root) is loaded into every session and is the base rule set; the
`kotlin-spring-guidelines` skill expands it.

## 3. How things trigger

| Kind | Triggers automatically? | Explicit use |
|---|---|---|
| **Skill** | Yes — Claude reads every skill's `description` and loads the skill when your request matches it | `/backplan ...` or "use the backplan skill to ..." |
| **Sub-agent** | Sometimes — Claude may delegate when a task matches the agent's description; `orchestrate` always uses them | "use the backend-developer agent to ..." |
| **Slash command** | **Never** — only when you type it | `/ask <task>` |

Automatic triggering is a judgement Claude makes from the wording of your request. If you want to
be sure, name the skill or agent. If a skill loaded that you did not want, say so ("no plan, just
fix it") — but note that new features are expected to start with a plan.

## 4. Default scenarios

### `kotlin-spring-guidelines` — the convention source

**Auto-triggers when:** writing, changing, reviewing, scaffolding or planning Kotlin code under
`src/main/kotlin/com/trenching/bot` — any implementation request such as "add the fill model",
"fix the rounding in P&L", "create the market observation entity".

**Also loaded by:** `backplan`, `orchestrate`, `review`, and both agents, always.

**Does not trigger for:** non-Kotlin work (Docker, CI, prose-only docs) or questions unrelated to
code.

**Explicit use:** `/kotlin-spring-guidelines` to load the rules before a free-form discussion.

### `backplan` — plan before building

**Auto-triggers when you ask for:** a plan, a design, an architecture, an approach, a module
contract, a schema design, or a new feature ("plan the paper fill model", "how should we design
wallet ingestion?", "design the positions table").

**Does not trigger for:** a small fix, a rename, a one-file change, or a review.

**Produces:** `docs/plans/<yyyyMMdd>-<slug>.md` with contracts, domain rules, migrations, tests,
acceptance criteria and open questions — then **stops and asks for approval**. Decisions that
affect simulation fidelity get an ADR in `docs/adr/`.

### `orchestrate` — deliver an approved plan with sub-agents

**Auto-triggers when:** you hand over an approved plan or a multi-part task for implementation, or
ask to orchestrate / delegate / parallelize ("implement plan 20260924-paper-fills",
"orchestrate: 20260924").

**Does not trigger for:** planning (it sends you to `backplan` if there is no approved plan) or a
single-file change.

**Does:** splits the work into parts with one owner per file, runs `backend-developer` per part
(in parallel only when files don't overlap), then one `backend-reviewer` pass, one fix pass if
needed, and archives the plan.

### `review` — check a change

**Auto-triggers when you say:** "review this", "check my change", "is this correct?", or after
`backend-developer` finishes.

**Does not trigger for:** writing code or planning.

**Does:** finds the change via read-only `git diff` / `git status`, checks it against the
requirement (task, plan or ADR) and reports `BLOCKER` / `MAJOR` / `MINOR` / `NOTE` findings with
failure scenarios. It **does not fix** anything unless you ask. It pays special attention to money
math, determinism, idempotency and price integrity.

### `backend-developer` agent

**Used when:** `orchestrate` dispatches a part, or you ask "use the backend-developer agent to
implement ...". Claude may also delegate to it on its own for a scoped implementation task.

**Works:** reads the rules and the plan, asks blocking questions first, writes the failing test
first, implements, runs Gradle, and reports in prose with a "Files changed" list.

### `backend-reviewer` agent

**Used when:** `orchestrate` runs its review part, or you ask "use the backend-reviewer agent on
this branch". Same checklist as the `review` skill, run in a separate context.

### `/ask` command

**Never automatic.** Type `/ask` (optionally with the task) when you want Claude to surface every
open question — blocking and non-blocking, each with a recommendation — **without writing any code**.
Use it before a task whose requirements feel fuzzy, especially anything touching simulation
semantics (fill rule, commitment, sizing, costs, exit rule, rounding).

## 5. Typical workflows

**New feature**

```
/ask plan the paper fill model          ← optional: settle open questions first
plan the paper fill model               ← backplan writes docs/plans/<date>-paper-fill-model.md
(review the plan, answer open questions, say "approved")
orchestrate the paper fill model plan   ← or: "implement it with the backend-developer agent"
(review result arrives; fixes applied if needed; plan archived)
```

**Small change or bug fix** — just ask ("the +3 offset uses the wrong base slot, fix it").
The guidelines load automatically; Claude writes a failing test first, fixes, runs Gradle, and
reports. Follow up with "review this" if you want a second look.

**Reviewing your own or a teammate's branch** — check the branch out, then "review the changes on
this branch against docs/plans/<plan>.md".

**An undecided simulation rule comes up** — Claude will stop and ask with a recommendation rather
than pick one. Answer it, and ask Claude to record it as an ADR in `docs/adr/`.

## 6. What Claude will and won't do here

Enforced by `.claude/settings.json` and the strict rules:

| Allowed without prompting | Asks you first | Refused |
|---|---|---|
| Read `src/**`, edit Kotlin sources, tests, test resources, `docs/**` | Edit `application-local.yml` / `application-prod.yml` | Read/write `.env`, `*secret*`, `*keypair*`, `*.pem`, `*.key`, `~/.config/solana/**` |
| `./gradlew compileKotlin / compileTestKotlin / test / assemble / build` | Edit Flyway migrations, Gradle files, `docker-compose.yml` | `solana-keygen` |
| Read-only `git status / diff / log / show` | `docker`, `bootRun`, `psql`, `solana`, `spl-token` | |
| | `git commit / push / reset --hard` | |

Beyond permissions, the rules tell Claude to: stay strictly read-only toward Solana, never call live
providers (RPC, market data, Telegram) on its own, keep secrets in environment variables only,
never edit an applied migration, not add dependencies without asking, and not change git state
unless you ask.

## 7. Changing the setup

- **Conventions** — edit `CLAUDE.md` for repository-wide rules; edit
  `.claude/skills/kotlin-spring-guidelines/references/*` for detail. Keep them consistent;
  `CLAUDE.md` wins on conflict.
- **When a skill triggers** — edit the `description` in its `SKILL.md` front matter; that text is
  what Claude matches requests against.
- **Permissions** — shared policy in `.claude/settings.json` (commit it); personal additions in
  `.claude/settings.local.json` (not committed).
- Changes to `.claude/` affect both developers — make them in a small, dedicated PR.
