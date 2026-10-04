# Successful-reference wallet history discovery

Accepted 2026-10-03 with Amendment A of the [real wallet replay plan](../plans/20261003-real-wallet-replay.md), approved by the user in chat.
Supersedes only the selection bullet ("Freeze up to 100 distinct address-reference signatures ... preserving failed transactions in the audit") of the [bounded wallet history capture decision](20261003-bounded-wallet-history-capture.md). Its other decisions stand.

## Context

The first user-run captures selected the latest 100 address references to the wallet, failed transactions included. All 100 were failed transactions packed into 9 consecutive slots. In the one inspected sample, the wallet was a read-only non-signer in another account's failed transaction, which failed before any swap. Under that pattern the latest 100 references are third-party noise and are unlikely to contain a signer swap for the decoder gate. Every detail request on them is spent without producing evidence. No agent contacted the provider; the evidence comes from the user's runs.

## Decision

- Select the latest N **successful** transactions referencing the wallet (N = `limit`, 1..100, default 100). Discovery scans standard `getSignaturesForAddress` newest-first in pages of 1,000 entries. An entry whose listing `err` is non-null is skipped: never detail-fetched, never stored. The manifest counts scanned and skipped-failed entries but does not keep failed signatures.
- Bound the scan by `scanPages` (default 20, maximum 50), validated by the command and the service before any provider call. Discovery stops at the first of `LIMIT_REACHED`, `HISTORY_END` (short or empty page) and `SCAN_BOUND`. `SCAN_BOUND` with fewer than N selected is a completed discovery of the bounded scope, not a full history.
- The scan cursor is the last *scanned* entry, not the last selected one. Selection, counters and cursor are checkpointed together after every page, including an all-failed page. A resume continues before the cursor and never admits newer signatures. A mid-page stop at the Nth success counts entries only up to that entry, so the same pages give the same manifest.
- Ordering, duplicate and conflict checks span skipped entries, using the cursor's slot as the frontier.
- Successful references signed by other accounts are still selected and recorded as `NON_SIGNER`.
- A selected transaction whose detail reports an error contradicts discovery and is `INVALID_TRANSACTION`, with no artifact; the capture stays incomplete.
- The manifest becomes schema version 2 (scan page limit, scanned and skipped-failed counts, scan cursor, stop reason). Version 1 directories are refused for resume and inspection; new captures go into a new directory.
- Read-only methods, budgets, rate limits, retries, timeouts, credential handling and transaction-version support are unchanged. A default run costs at most 1 + 20 + 100 = 121 requests.

## Alternatives

- **More pages of all references:** spends detail requests on failed noise.
- **Helius-specific filtered history APIs:** return a vendor-parsed contract, conflicting with the original decision to stay independent of vendor parsing.
- **Signer-only selection:** standard RPC cannot filter by signer at discovery, so it would need a detail request per candidate.
- **A time or slot window instead of a page bound:** possible, but a separate user decision; a page bound keeps request cost predictable.

## Consequences

The audit no longer contains failed transactions, only their count over the scanned range, so failure patterns cannot be studied from these captures. The `FAILED` outcome stays in the format but is expected to be 0. Existing version 1 diagnostic captures cannot be inspected with the new command. A bounded scan can complete with fewer than N transactions, and the summary says so through its stop reason. Successful third-party transactions may still dominate the selection; the `NON_SIGNER` / `UNCLASSIFIED` split shows it.

## Revisit when

No signer transaction appears within 50 pages, successful copy-bot traffic dominates the selection, or the user wants a time/slot window or a larger bound. Report such evidence and revisit scope with the user rather than raising bounds automatically.
