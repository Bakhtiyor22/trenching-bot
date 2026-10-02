---
name: review
description: >
  Structured code review for the trenching-bot backend (Solana paper-trading research system).
  Use after an implementation to check that it meets the requirement, keeps module boundaries and
  this repo's conventions, is bug free — especially numerically, deterministically and
  idempotently — and is maintainable by a developer seeing it for the first time. Trigger on
  "review this", "check my change", or after `backend-developer` finishes. Reviews only the
  touched change. Do not trigger for planning or for writing code.
---

# trenching-bot Review

Review **what was implemented**, against **what was asked for**.

Use `kotlin-spring-guidelines` (and `CLAUDE.md`) as the convention source and
`references/strict-rules.md` as the rule list. A strict-rule violation is a finding, not a
preference.

## Environment restrictions

Same rules as everywhere in this repo — `strict-rules.md` Sections A–C. The one addition here:
**do not fix code** unless the user explicitly asked for fixes. Report findings.

## Inputs

1. The requirement — the task text, or the plan in `docs/plans/`, plus any ADR it cites.
2. The change — `git status` / `git diff` (read-only git is allowed), or the files the implementer
   listed.
3. The surrounding code the change depends on or is depended upon by.

If you cannot identify the requirement, ask for it before reviewing.

## Scope

- Only the change and what it directly affects.
- Feature-by-feature, not file-by-file.
- Pre-existing problems go under `[NOTE]`, explicitly out of scope.

## Checklist

Work these in order. Section 1 is the one reviews most often skip, and the one that matters most.

### 1. Does it meet the requirement?

- Restate the requirement in one sentence. Map each stated behaviour to the code implementing it.
- Name anything **unimplemented, partially implemented, or silently reinterpreted** — especially a
  simulation rule (fill eligibility, commitment, sizing, costs, exit, rounding) that the code
  decided on its own instead of following the plan/ADR.
- Check the implied negative cases: failed/unknown/duplicate transaction · out-of-order input ·
  missing/stale/out-of-window price · zero or negative amount · decimals mismatch · `u64` beyond
  `Long` · illegal transition · duplicate close · competing exits in one slot · rerun of the same
  dataset · provider timeout/rate limit · unconfigured optional integration.
- Check for **collateral change**: a public port or value type altered, a fixture schema changed
  without a `schemaVersion` bump, a config property renamed.

### 2. Is it correct?

- **Read-only toward Solana.** Any transaction building, signing, sending or key handling is a
  BLOCKER.
- **Numbers.** `Double`/`Float` anywhere near money, price, quantity, fee or P&L — BLOCKER. Parsing
  a vendor decimal through `Double` — BLOCKER. `divide`/`setScale` without a `RoundingMode`;
  `BigDecimal.equals` instead of `compareTo`; raw `u64` amounts in a `Long` without a bound; decimals
  applied twice or not at all.
- **Determinism.** `Instant.now()`, `UUID.randomUUID()`, unseeded `Random`, or iteration over an
  unordered collection affecting output, in domain/application code.
- **Price integrity.** Any silent fallback — interpolation, "latest available", widening the window —
  instead of an explicit rejection. A fill that does not reference its observation.
- **Idempotency.** Can a re-delivered or replayed event duplicate a signal, intent, fill, position
  or exit? Is it enforced by a unique constraint as well as in code?
- **Provenance.** Missing run id, source event, strategy version, config snapshot, price source or
  fee/slippage model on a new record.
- **Nullability.** Every `!!` is a finding. A platform type from JDBC/Jackson/Java into a non-null
  Kotlin type is a latent NPE.
- **Schema.** A new Flyway/`.sql` migration file (there is no Flyway for now) — BLOCKER. A unique
  key the code relies on that is not declared in the entity's `@Table` — BLOCKER. A schema change
  with no SQL stated for production (`prod` validates). A destructive change
  without approval. Missing unique constraint on external identity. Missing index for a new query
  on a growing table.
- **Transactions.** Multi-write path without `@Transactional`; `@Transactional` on a `private` or
  self-invoked function (does nothing); on an adapter or repository; a remote call inside one.
- **N+1 and unbounded reads.** Repository call or lazy traversal inside a loop; a query over a
  growing table without a bound.
- **Exceptions.** Swallowed, downgraded, cause dropped, `catch (e: Throwable)`, exception used for
  an expected outcome that should be a sealed result.
- **Integrations.** No timeout; retry of a non-idempotent or permanent failure; vendor DTO or vendor
  exception escaping the adapter; secret in a URL that gets logged; call to a live provider in a
  default test.

### 3. Module boundaries and layering

- Another module's internals, entities, repositories or vendor DTOs imported — BLOCKER.
- `common` grown with non-shared or non-primitive content.
- Adapter (controller, Telegram handler, scheduler, replay entry point) with business logic,
  repository access, `@Transactional`, or logging.
- Service returning an entity across the module boundary; **a DTO or mapper defined inside a service
  file**.
- Mapper that decides, queries, or does I/O.
- `XyzService` + `XyzServiceImpl` with no boundary or second implementation to justify it.
- Dependency cycles.

### 4. Clean code

- One responsibility per class and function.
- No duplicated logic; literals used more than twice in a constants `object`.
- Guard clauses, exhaustive `when` without `else` on sealed/enum types, immutable `val`s.
- Names describe intent. No dead code, no commented-out code, no unused imports.
- **Comments are the exception.** One restating the code or narrating the change is a finding; a
  genuine *why* stays.
- Repo idiom: primary-constructor injection, `LoggerFactory.getLogger(X::class.java)`,
  parameterized `key=value` logs, `data class` DTOs, plain `class` entities with
  `@Enumerated(STRING)`, `LAZY` associations, explicit column constraints, `T?` repository returns,
  Jackson 3 imports.

### 5. Maintainability

*Someone joins tomorrow and opens only these files — can they understand and change it safely?*

- Can the simulation flow be followed without hidden state?
- Are scales, rounding modes, offsets and thresholds named and in one place?
- On failure, will the log line identify what broke — run, event, signature, slot, intent, position?
- Would the next strategy / exit rule / provider adapter fit naturally?
- Is the test readable as a specification?

### 6. Tests

- New logic without a test is a **MAJOR** (a BLOCKER for money/P&L, fill selection or idempotency
  logic).
- Do unit tests run without Spring, database or network? Fixed `Clock`, explicit slots, no sleeps?
- Is there a negative assertion (nothing persisted, nothing sent, no second fill)?
- Are rounding and offset boundaries parameterized?
- Is the P&L asserted against a hand-calculated value, not against the code's own output?
- **Does the test read as a specification of the requirement, or a restatement of the
  implementation?** Mock-every-call-and-verify tests are a MINOR — say what a requirement-driven
  version would assert.
- Fixtures sanitized, versioned, no keys. Live tests tagged `live`.
- Were the claimed commands actually run, and does the result match? A Testcontainers failure
  because Docker is down is an environment gap, not a finding against the change — but it means
  the change is unverified.

### 7. Contract and documentation drift

- Public port/value type or fixture schema changed without the other side and fixtures updated.
- README not updated for a new configuration variable, profile behaviour or run step.
- A fidelity/reproducibility/provider decision made without an ADR.
- The plan not archived once delivered.

## Output

Findings first, most severe first, each with a file reference and a concrete failure scenario:

```
[BLOCKER] papertrading/FillModel.kt:48 — <one-sentence defect>
  Fails when: <concrete inputs/state → wrong result>
  Fix: <shortest correct change>
```

Severity:

- **BLOCKER** — wrong result, non-reproducible or duplicated simulation data, startup failure,
  secret/key exposure, anything writing to Solana, requirement not met.
- **MAJOR** — real edge-case bug, missing transaction, N+1 on a hot path, contract drift, no test for
  the core new rule.
- **MINOR** — maintainability: naming, duplication, structure, a comment that lies.
- **NOTE** — pre-existing issue nearby, explicitly out of scope.

Then close with:

1. **Requirement coverage** — implemented / partial / missing, item by item.
2. **Schema and startup risk** — entity/table changes, the SQL production needs, anything
   destructive.
3. **Residual risk** — what could still break that the tests do not cover.
4. **Verification gaps** — what was not run and should be.

Rules for the review itself:

- **Do not paste the reviewed code.** Reference `path:line`; a one-line fix sketch is fine.
- Keep it short. A clean review is two sentences, not a page.
- **Escalate rather than decide.** A finding that needs a product or simulation-semantics decision
  is a **question to the user** with a recommendation (`references/ask-protocol.md`), not a finding.
- Find real issues. Do not pad with style nitpicks.
- Every finding needs a failure scenario; if you cannot describe how it breaks, it is a MINOR at
  most — or not a finding.
- If nothing is wrong, say so, and still fill in residual risk and verification gaps.
