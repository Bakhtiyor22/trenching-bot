---
name: backplan
description: >
  Architecture and implementation planning for the trenching-bot backend (Solana paper-trading
  research system). Use when the user asks for a plan, design, architecture, approach, module
  contract, schema design, or a written plan file for new functionality — wallet activity
  ingestion, market data, strategy, paper execution, positions and P&L, replay, Telegram,
  provider adapters, persistence. Do not trigger for direct coding of a small change, trivial
  fixes, or pure review work.
---

# trenching-bot Planning

Produce a plan that tells an implementer **what to build, which contracts and invariants matter,
and what must stay in sync**. No production code in the plan — signatures, field lists and table
columns are fine; implementations are not.

Use `kotlin-spring-guidelines` for conventions. Read
`.claude/skills/kotlin-spring-guidelines/references/strict-rules.md` and
`references/trading-domain.md` before deciding anything.

## Environment restrictions

Planning does not exempt you from `strict-rules.md` Sections A–C (secrets, Solana read-only, no
live providers, git). If the plan depends on a secret or a provider credential, name the
**environment variable** and list it under Open Questions.

## Understand first

Read, in this order, only what the change actually touches:

1. `CLAUDE.md` and `.claude/skills/kotlin-spring-guidelines/SKILL.md` + the relevant references
2. `README.md` — layout, profiles, configuration
3. `docs/WEEKLY_EXECUTION_PLAN.md` — agreed module boundaries, contracts (§3), data requirements
   (§5), test strategy (§6) and the open decisions (§9)
4. `docs/adr/` and `docs/plans/` (+ `archive/`) for related, superseding or conflicting decisions
5. The real code in the modules involved, including the entities (the schema — there is no Flyway
   for now) and `docs/schema/` for history

If something you need is undecided or undocumented, say so in the plan rather than guessing.

## Decide ownership first

Every plan states **which module owns the change** and why, and whether it:

- stays inside one module's internals (lowest risk), or
- adds to a module's public contract (other modules may depend on it), or
- **changes** an existing public contract or the replay fixture schema (needs both developers'
  agreement — call it out).

## What the plan must decide

Every plan answers these. Skip one only by saying why it does not apply.

**Ownership and contracts**
- Owning module; new sub-packages if warranted
- Ports added or changed, with signatures; which adapters implement them (fixture adapter always)
- Value types / commands / results crossing a module boundary, by name
- Whether any change is a **contract break** — say so loudly

**Domain rules**
- The invariants, as testable sentences
- The simulation-semantics decisions this work depends on (fill rule, commitment, sizing, costs,
  exit rule, rounding, concurrency) — cite the ADR/plan that settled each, or list it as an Open
  Question. Never invent one.
- Number types, scales and `RoundingMode`s for every calculated value
- Determinism: what needs an injected `Clock` / id generator; how ids survive replay
- Idempotency keys and where they are enforced (application **and** database)
- Provenance fields each new record carries
- The negative cases, concretely: failed/unknown/duplicate transaction, out-of-order input,
  missing/stale/out-of-window price, zero or negative amount, decimals mismatch, `u64` overflow,
  illegal state transition, duplicate close, competing exit triggers in one slot, rerun of the same
  dataset, provider timeout/rate limit, unconfigured optional integration

**Persistence**
- New tables/columns with types and constraints, as entity changes (`@Column`, and
  `@Table(uniqueConstraints, indexes)`), plus the SQL production needs since `prod` only validates
- Unique constraints for external identity and idempotency; indexes for the query patterns
- Whether anything is destructive (drop/rename/narrow/backfill) — needs approval and a rollback note
- Entities and repositories per module; associations by id across modules
- Query bounds on growing tables; N+1 avoidance

**Transactions and integration**
- Transaction boundaries; no remote call inside one
- Provider calls: which port, timeouts, retries, degraded behaviour, rate limits, cost
- Whether a live provider/credential is required, or fixtures suffice (they should for the core)
- Configuration properties added (prefix, validation, prod default or fail-fast)

**Verification**
- Unit test classes and the behaviours each must prove (test-first)
- Integration tests (Testcontainers) and the E2E/replay assertions affected
- Fixtures to add or version-bump
- Commands to run

**Documentation sync**
- README sections that change
- ADRs to add (decisions affecting fidelity, reproducibility, contracts, provider choice)

Make the decision when the sensible path is clear. Present options only when the trade-off is real
and consequential — then recommend one.

## Output

**Every new feature gets a persisted plan** at `docs/plans/<yyyyMMdd>-<slug>.md` (date prefix, so two
developers never collide on a number). Create `docs/plans/` if it does not
exist. Only a small, already-scoped change may be presented inline instead.

Lifecycle: approve → implement → review → update README/ADRs → move to `docs/plans/archive/` with a
`## Delivered` note. **Nothing is implemented before the user approves the plan.**

```md
# {Title}

> One-line summary.

Status: Proposed | Approved | Delivered

## Context

What exists today and why the change is needed. Name the files/modules that own current behaviour.

## Scope

### In scope
### Out of scope

## Key Decisions

Decision → rationale. Cite ADRs. Rejected alternatives only where the choice was genuinely close.

## Module Contracts

Ports, value types, commands/results — by name, package and signature. Mark breaking changes.

## Domain Rules

Numbered, testable invariants — including negative cases, number types/scales/rounding,
determinism, idempotency, provenance.

## Data Model

Entities, tables, columns, constraints, indexes, and the SQL production needs. Destructive changes flagged with a
rollback note. "No schema change" if none.

## Transactions & Integration

Boundaries; provider calls, timeouts, retries, degradation; configuration properties.

## What To Implement

Ordered, meaningful units of work — deliverables, not chores. Dependencies between them, and which
can run in parallel without two owners touching the same files.

## Verification

Test classes and the behaviours each must prove; fixtures; commands.

## Documentation Sync

## Acceptance Criteria

Observable and specific: a persisted row, a constraint, a sealed outcome, a P&L value, a log line.

## Open Questions

Each with a recommendation.
```

## Writing rules

- Units of work are deliverables ("+2 entry fills from the first eligible observation or rejects"),
  not chores ("add a repository method").
- Negative cases are concrete. "Handle edge cases" is not a plan.
- Acceptance criteria are observable. Not "works correctly".
- Do not prescribe git workflow beyond what the team already agreed.
- Do not prescribe agent sequencing unless the user asked for orchestration — that is
  `orchestrate`'s job.
- A decision that affects simulation fidelity or reproducibility gets an ADR in `docs/adr/`
  (`<yyyyMMdd>-<slug>.md`: context, decision, alternatives, consequences, revisit trigger).

## Reporting

Present the plan's **substance in prose** — do not paste the plan file into the console. Say where
it lives, summarise the decisions and the open questions, and ask for approval.

**Ask, don't assume**, on anything in the "always ask" list of `references/ask-protocol.md` —
especially undecided simulation semantics, contract changes, destructive schema changes, new
dependencies and anything that would touch Solana beyond reading.

## Quality check

Before presenting, verify:

- You read the current code and entities, not just the docs
- The owning module is named, and cross-module access goes only through public contracts
- The plan matches the real stack (Kotlin 2.3, Boot 4.1, Jackson 3, entity-driven schema with `update` locally and
  `validate` in prod, JUnit 5 +
  Testcontainers)
- Every simulation-semantics dependency cites a decision or is an Open Question
- No `Double`; every calculated number has a scale and rounding mode
- Idempotency is enforced at both application and database level
- Every business rule has at least one negative case
- The core flow is testable with fixtures only — no live provider required
- Schema changes are additive, or flagged as destructive with a rollback note
- Acceptance criteria are checkable
- Nothing requires reading a secret, a keypair, or calling a live provider
