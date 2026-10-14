# Capture to paper implementation — milestone 1

Status: In progress — independent market cases with bounded capture recovery and console controls.

This plan implements the first milestone of the approved capture and paper improvement plan. It covers independently usable market cases plus bounded recovery and console controls. It does not deliver provider-concurrency or bulk-history work, a new strategy, portfolio evaluation, continuous paper ingestion, or chart changes.

## Implementation scope

- Derive stable case identifiers from the market window mint, anchor signature, and inclusive bounds.
- Expose per-case acquisition counts and blockers from the existing market manifest.
- Validate one selected case by verifying only its selected transaction artifacts. A malformed whole manifest remains a capture-wide failure; a corrupt sibling transaction artifact does not block another case.
- Finish detail fetching and checkpoint the manifest after each completed discovery window, before listing the next window. Shared signatures remain one manifest record and one detail request.
- Capture reference-rate minutes from the selected case in a caller-provided case-specific rates directory.
- Export one selected case as an immutable, checksum-verified v3 dataset through the existing writer and loader. Keep the existing full-capture decode and export completeness gates unchanged.
- Carry exact wallet, source signature, slot, instruction, window bounds, parameters and extractor provenance in the case-export result and validate the expected identity in the request.
- Reject an anchor transaction containing multiple normalized wallet trades, since v3 cannot split that transaction without distorting its wallet activity.
- Persist bounded recovery accounting for capture operations, including attempt, request and byte limits, the next retry time and provider `Retry-After` when supplied.
- Resume eligible interrupted work after restart, while pause and cancel remain stopped and stale attempts remain blocked.
- Provide pause, resume, retry and cancel controls without deleting already checkpointed evidence or validated cases.
- Expose case progress and case preparation in the local console. A case is ready for preparation only after its selected artifacts validate; downloaded details alone do not make it ready.
- Prepare case-specific rates and a case-specific immutable dataset while retaining the existing full-capture export and dataset import paths.

## Acceptance checks

- A fully captured case reaches selected-case validation while a sibling window is still discovering.
- A missing shared transaction blocks each case that selected it.
- A corrupt sibling artifact does not prevent decoding the selected healthy case.
- Case rates contain only minutes used by the selected case.
- Case export selects the exact first accepted wallet buy and its case observations/rates, and carries provenance alongside the v3 result.
- A legacy full export still refuses any incomplete capture.
- Existing v3 checksums and legacy decode/export meaning remain unchanged.
- Retries stop at the configured attempt and cumulative request/byte budgets, preserve provider retry delays, and do not restart paused or cancelled work after process restart.
- Case rate/export operations expose their operation id and state through existing polling. A failed or interrupted case operation is retried with a new idempotent request; main-batch pause/cancel controls do not target the separate case worker.
- The console distinguishes downloaded case evidence, `READY_TO_VALIDATE`, validated readiness, rate preparation and exported dataset availability.

## Verification

Run the marketdata and replay unit test classes for case decoding, capture ordering, scoped rates, wallet planning and case export. Run the console operation tests for recovery budgets, restart behavior, retry timing and controls, plus the frontend capture tests for case actions and operation polling. The repository-wide build remains a supervisor checkpoint after all parallel source changes are ready.

## Schema and compatibility

No database schema changes. The market manifest format and replay dataset v3 bytes/checksum semantics remain unchanged. Case provenance is returned separately because v3 has no field for it. Recovery state remains in the console's file-backed operation records. The complete approved improvement plan remains in progress after this milestone; provider throughput, the new strategy, continuous paper ingestion and chart work are still pending.

## Accepted follow-on decisions

These decisions constrain later milestones and do not change the legacy strategy or dataset semantics in this implementation:

- The new paper strategy must disqualify a token when the tracked wallet had any earlier interaction, receipt or transfer. Incomplete or unknown earlier history remains `UNPROVEN`; it never qualifies by absence of evidence.
- The chart will use total token supply and label the valuation basis explicitly. It will also state whether the displayed denomination is USD or USDT rather than treating them as interchangeable without disclosure.

Live trading, signing and transaction submission remain outside both this milestone and the accepted follow-on decisions.
