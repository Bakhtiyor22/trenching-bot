---
description: Surface every open question about the current task and stop — no code, no assumptions.
argument-hint: [optional: the task you are about to start]
---

Do not write, edit, or plan any code in this turn.

Your only job right now is to surface **everything you are uncertain about** in the task below, so
the user can settle it before work starts.

Task: $ARGUMENTS

(If no task was given, use the task currently under discussion.)

## What to do

1. Read enough of the codebase to know what you *don't* know. Read
   `.claude/skills/kotlin-spring-guidelines/references/ask-protocol.md` for the question format and
   the three checks that answer most questions without asking.
2. Work out which uncertainties would actually **change the code you write**. Discard the rest.
3. Ask them all at once, in the format below.

## Format

Group into two lists.

**Blocking** — you cannot start, or would likely build the wrong thing:

```
1. {Question, one sentence.}
   Why it matters: {what changes depending on the answer}
   My recommendation: {the option you would take, and why}
```

**Non-blocking** — you can proceed on a stated assumption:

```
1. {Question}
   Assuming: {the assumption you will proceed on if the user says nothing}
```

Then one line: what you will do if the user answers nothing at all.

## Rules

- **Maximum 5 blocking questions.** If you have more, the task is too big — say so and propose
  splitting it, or writing a plan first with `backplan`.
- **Every question gets a recommendation.** You have read the code; the user has not.
- **No questions whose answer is in the repo.** Read `CLAUDE.md`, `README.md`, `docs/adr/*`,
  `docs/plans/*`, `docs/WEEKLY_EXECUTION_PLAN.md`, the migrations and the existing code first.
- **Run the three checks in `ask-protocol.md` first**: is it already decided in an ADR/plan, what do
  the migrations say, and which module owns the contract.
- **No questions about conventions.** `CLAUDE.md`, `kotlin-spring-guidelines` and
  `references/strict-rules.md` decide those. If two rules genuinely conflict, that is worth asking —
  quote both.
- **Never ask for a secret's contents.** Ask for the environment variable name to bind, or for the
  user to set it.
- Ask about **behaviour and intent**, not implementation preference. "Should a Decu sell force our
  exit, or only local exit rules apply?" is a good question. "Should I use `map` or a for-loop?" is
  not.
- **Simulation-semantics questions are always worth asking** when no ADR settles them — fill rule,
  commitment, sizing, costs, exit tie-breaks, rounding, position concurrency. They change what every
  result means.
- If you genuinely have no blocking questions, say exactly that in one line, list your assumptions,
  and stop. Do not invent questions to look thorough.
