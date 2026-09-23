# Ask Protocol

When to stop and ask, and how to ask well. Applies to every agent and skill in this repository.

## The rule

**Ask when the answer would change the code. Decide when it would not.**

A careful colleague does not ask permission to name a variable, and does not silently invent a
simulation rule. Everything else is judgement — these lists make the edges concrete.

## Always ask — never proceed on an assumption

- **Anything that would touch Solana beyond reading** — building, signing or sending a transaction,
  handling a keypair. The answer is almost certainly "no"; ask anyway, do not build it.
- **An unresolved simulation-semantics decision.** `docs/WEEKLY_EXECUTION_PLAN.md` §9 lists them:
  wallet identity and scope, which actions are copied, entry offsets, the eligible-fill-price rule,
  commitment level, sizing, fee/slippage model, exit rule and tie-breaks, position concurrency,
  rounding and P&L currency. If the plan or an ADR has not settled the one you need, it is a
  product decision, not a coding decision.
- **A change to a public module contract** — a port signature, a cross-module value type, the
  replay fixture schema, or anything the other developer's module consumes.
- **A destructive or data-changing migration** — drop, rename, narrowing, type change, `NOT NULL`
  on a populated table without a default, a backfill — or editing a migration that may already be
  applied.
- **Adding or bumping a dependency** (MapStruct, a WebSocket client, a provider SDK, MockK, ...).
- **Calling a live provider or messaging a real Telegram chat.**
- **A new secret or required prod variable**, or anything touching `application-prod.yml`.
- **Deleting or overwriting** anything you did not create in this session.
- **Changing a repository-wide convention** in `CLAUDE.md`.

For these, "I'll make a sensible choice and flag it" is **not** acceptable. Ask first.

## Ask when it genuinely changes the work

- Two readings of the requirement lead to materially different code.
- Two rules (in `CLAUDE.md`, `strict-rules.md`, or the weekly plan) genuinely conflict for this case.
- The plan and the code disagree about current behaviour.
- **Which module owns the concern** is genuinely unclear and the answer changes the contract.
- The change is technically possible but you believe it is the wrong thing to build.

## Decide yourself — do not ask

- Naming, file placement inside a module, function decomposition, test structure.
- Anything `CLAUDE.md`, `kotlin-spring-guidelines` or `strict-rules.md` already answers.
- Anything the code, the migrations, the README, an ADR, the plan or an existing test answers — go
  read it. **Asking a question the repository already answers is worse than not asking.**
- Whether to write a test (yes, for logic-bearing code).
- Obvious edge-case handling that follows from an existing invariant.

For these: decide, state the assumption in one line in your output, and keep going.

## Verify before you ask

Three checks answer most questions here without involving anyone:

1. **Is it already decided?** Search `docs/adr/`, `docs/plans/` and `docs/WEEKLY_EXECUTION_PLAN.md`
   for the decision (fill rule, commitment, rounding, ...). A decision recorded there is the answer.
2. **What does the schema say?** Read the migrations in `src/main/resources/db/migration`, newest
   last, for the table, its constraints and unique keys — not a live database, not only the entity.
3. **Who owns it?** Find the module whose public contract declares the type or port. If it is
   another module's contract, changing it is an "always ask".

## How to ask

**Batch.** Gather every question and ask once, after reading and before writing.

**Lead with a recommendation.** You have read the code; the user has not.

```
1. When no observation exists in the exact target slot, should the +2 entry fill from the first
   observation after it, or be rejected?
   Why it matters: decides whether FillModel looks ahead and needs a max-wait setting, or rejects.
   Recommendation: first observation at/after the target slot within a configured max wait
   (default 2 slots), otherwise reject with MISSING_PRICE — conservative and deterministic, and
   the plan already leans this way.
```

**Separate blocking from non-blocking.** Blocking = you stop. Non-blocking = you proceed on a
stated assumption the user can correct later.

**Cap it at five blocking questions.** More than five means the task is under-specified — say so,
and propose splitting it or writing a plan first (`backplan`).

**Never ask for a secret's contents.** Ask for the *variable name* to bind, or for the user to set it.

## Where questions belong

| Situation | Put the questions |
|---|---|
| Before starting work | In your reply, then stop |
| Writing a plan | Under `## Open Questions` in the plan file, summarised in your reply |
| Mid-implementation, non-blocking | Proceed on the assumption; list it in your output |
| Mid-implementation, blocking | Finish every part that does **not** depend on the answer, then stop and ask |
| Reviewing | As a question in the review, not a finding |
| Orchestrating | You ask the user; do not let a sub-agent decide it |

## Anti-patterns

- Asking a question whose answer is in the repo.
- Asking a question with no recommendation.
- Asking for permission to follow the documented conventions.
- Drip-feeding one question per turn.
- Proceeding on an assumption on anything in the "always ask" list.
- Building the whole thing and asking at the end.
