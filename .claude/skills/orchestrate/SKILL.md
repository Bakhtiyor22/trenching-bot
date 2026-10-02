---
name: orchestrate
description: >
  Splits a plan or task for the trenching-bot backend into properly-scoped implementation parts,
  dispatches each to a sub-agent, and manages them through review to completion. Use when a plan
  or a multi-part task is handed over for implementation, or when the user asks to orchestrate,
  delegate, or parallelize work. Do not trigger for planning itself (use `backplan`) or for a
  one-file change.
---

# trenching-bot Orchestration

**Your job: take a plan or task, divide it into proper implementation parts, hand each part to a
sub-agent, and manage them to done.** You coordinate; you do not implement work an agent owns.

| Part | Agent / skill |
|---|---|
| `[impl]`, `[fix]` | `backend-developer` agent |
| `[review]` | `backend-reviewer` agent, or the `review` skill |
| Conventions for every agent | `CLAUDE.md` + `kotlin-spring-guidelines` + `references/strict-rules.md` |

## Inputs

- A plan in `docs/plans/*.md` — the normal case. Shorthand like `orchestrate: 20260924` or a slug
  resolves to the matching file.
- Or an approved inline task description.

Read the plan first, then only enough code to know which files each part owns.

**If the work is a new feature and there is no approved plan, stop and run `backplan` first.**
Orchestrating an unapproved plan multiplies a wrong decision across several agents.

## Carry into every brief

The agents already hold the full restriction set (secrets, Solana read-only, no live providers, no
git changes). Three things you must still decide and pass down, because an agent cannot:

- **The simulation-semantics decisions the part depends on**, with the ADR/plan reference. An agent
  that meets an undecided one stops and reports to you; you ask the user.
- **Schema ownership.** Exactly one part owns all new tables and schema changes in a run, so table
  ownership stays coherent. Other parts depend on it.
- **Contract ownership.** Exactly one part defines each shared port/value type; consumers wait.

## Dividing the work

1. Create all implementation parts first.
2. Add one review part after implementation completes.
3. Add one fix part only if the review finds real issues.

How to cut the parts:

- **A part is a meaningful deliverable** ("fill model selects the first eligible observation or
  rejects"), not a micro-step.
- **One owner per file.** Two agents editing the same file is the main failure mode.
- **Cut along module boundaries** — `walletactivity`, `marketdata`, `strategy`, `papertrading`,
  `portfolio`, `replay`, `telegram`. Modules that talk only through public contracts can be built in
  parallel once the contract exists.
- **Shared foundations go first, alone:** public contracts and value types in `common` or a module's
  public package, the replay fixture schema, new entities/tables, `config/`, `build.gradle.kts`. Sequence them
  before their consumers.
- **`build.gradle.kts`, `application*.yml` and `replay/**` wiring are single-owner** in a run.
- **Keep the count honest.** Three real deliverables is three parts, not eight.
- **Documentation is part of each deliverable**, not a separate part.

Labels: `[impl] Paper fill model` · `[review] Touched changes` · `[fix] Review findings`

## The brief each sub-agent gets

1. **Objective** — the deliverable, in one or two sentences.
2. **Scope** — the module/files it owns, and the files it must **not** touch.
3. **Decisions** — the simulation-semantics decisions it relies on, with references.
4. **Dependencies** — what is already done (contracts, entities) and what it must not assume.
5. **Acceptance criteria** — copied from the plan, observable.
6. **Verification** — test-first; `./gradlew compileKotlin` in the loop, the targeted test class,
   `./gradlew build` at the end (Docker needed for Testcontainers).
7. **Reporting** — prose only, no code dumps; schema impact stated; "Files changed" list; ask rather
   than assume on anything critical (the agent asks *you*, you ask the user).

## Running them

- **Fresh agent per part.**
- **Parallel only where file ownership is genuinely disjoint.** When in doubt, sequence.
- Do not duplicate an agent's work locally while it owns the part.
- If an agent reports a blocker, resolve the dependency or re-scope — do not guess past it.
- **Treat an agent's report as a claim to check.** The review part verifies it.

## Review cycle

- One review pass after all implementation parts complete.
- Real issues → **one** fix part with the full finding list.
- Re-review only if the fix changed behaviour beyond the findings.
- No repeated review→fix loops unless the user asks.

## Closing out

1. Confirm README and ADRs were updated where the change affected them.
2. **Consolidate the schema impact** — all entity/table changes across parts, the SQL production
   needs, and anything destructive — in one statement.
3. Move the plan to `docs/plans/archive/`, appending `## Delivered`: date, what shipped, where it
   differed from the plan and why, docs updated.
4. Run final verification once:

```bash
./gradlew build
```

If Docker is not running, Testcontainers tests cannot run — report that as an unverified result,
never as green, and never disable a test to get past it.

## Hard rules

1. No git state changes — no commits, branches or pushes by you or any agent — unless the user asks.
2. Never widen scope beyond the approved plan. Discovered work is reported, not absorbed.
3. Review only the touched changes.
4. Never let two agents own the same file at the same time.
5. Call out blocked dependencies instead of guessing through them.
6. **Ask the user on anything critical** — undecided simulation semantics, a contract change, a
   destructive schema change, a new dependency, anything touching Solana beyond reading. Do not let a
   sub-agent decide it.

## Output

Short, in prose. **No code, no diffs, no file dumps.**

1. **Parts** — label, agent, scope, status, dependencies.
2. **Execution order** — what ran in parallel, what was sequenced, why.
3. **Result** — what was implemented, files changed, review findings and resolution.
4. **Schema impact** — the consolidated statement.
5. **Verification** — commands run and their real results.
6. **Open items** — blocked work, deferred findings, decisions the user still owns.
