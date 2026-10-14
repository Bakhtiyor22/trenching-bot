# Independent market-case validation and export

Accepted 2026-10-12 as milestone 1 of the approved [capture and paper improvement plan](../plans/20261012-capture-paper-implementation.md).

## Context

Market capture manifests can contain several wallet-planned windows. One unfinished or corrupt sibling should not hide a complete case from offline paper replay. The existing full-capture decoder and export deliberately require whole-capture completeness, and replay dataset v3 is immutable and checksum-addressed.

## Decision

A case is the exact wallet's planned first accepted buy for a mint and its inclusive market window. Its stable id hashes the mint, anchor signature and bounds. The existing `RecordedWalletPlanning` remains authoritative for wallet eligibility and anchor selection; no new eligibility rule or strategy behavior is introduced.

Manifest progress is available without hashing transaction artifacts. Explicit case inspection validates only the selected case's artifact hashes and transaction identities. A corrupt whole manifest blocks all cases; a corrupt selected artifact blocks its case; an unrelated sibling artifact does not. A transaction missing from multiple selected windows blocks each dependent case.

Capture completes and durably checkpoints details for each discovered complete window before scanning a later window. Shared signatures remain deduplicated in the manifest and fetched once.

Case-scoped rates use the exact validated case observations and a dedicated caller-selected rates directory. Case export verifies the wallet, signature, slot, instruction, bounds and window parameters supplied in the request against the recorded wallet plan. It filters the dataset to the exact anchor trade and case observations/rates. An anchor transaction with multiple normalized wallet trades is refused because a dataset containing only one instruction would misrepresent that transaction.

Case export uses the existing v3 writer, loader, checksum and write-once behavior. Provenance travels in the result alongside the v3 artifact; it is not added to the v3 bytes. Full-capture decode and export retain their existing all-window completeness requirements.

## Consequences

Cases can progress independently into validation and paper replay, while the console can poll manifest progress without repeatedly reading raw artifacts. Current first-buy-in-sample planning limitations remain in force. The database schema, manifest format, v3 dataset format, checksums and paper strategy are unchanged.
