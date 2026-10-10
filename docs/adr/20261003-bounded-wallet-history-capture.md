# Bounded mainnet wallet history capture

Accepted 2026-10-03 with the [real wallet replay plan](../plans/archive/20261003-real-wallet-replay.md), approved by the user in chat.

## Context

Replay already accepts canonical v1 inputs and persists immutable experiments, but its sample transactions are synthetic. The user selected mainnet, the latest 100 transactions referencing the supplied wallet, and configured Helius locally. Their console screenshot showed successful mainnet-beta connectivity; the newest-five diagnostic found no successful transaction. That diagnostic does not import history or establish that the wallet has never traded. No agent independently queried this wallet or provider.

## Decision

- Adopt Helius standard HTTP RPC for a bounded, user-run research capture. This does not imply a paid upgrade, production delivery guarantee or complete token-account history. No agent live-provider call is authorized.
- Freeze up to 100 distinct address-reference signatures at confirmed commitment, preserving failed transactions in the audit. Fetch details for that immutable selection and record unavailable, incompatible, invalid, failed and non-signer outcomes. Successful signer transactions are unclassified until an evidence-backed swap decoder is implemented.
- Verify mainnet genesis identity before acquisition. Keep signature, slot, commitment, acquisition time, file hash/size and coverage status. Raw results and the manifest are local research artifacts, not application database records or committed fixtures.
- Capture is opt-in and independent of Spring Boot database startup. Offline inspection uses saved files and no credentials. Environment files and secret values are never read by the agent; the user's capture process consumes its own environment configuration without logging the endpoint.
- Preserve replay schema v1 and accepted simulation semantics. No entity, table, dependency or production profile change is required.
- Actual swap program/route support and instruction identity remain gated on sanitized capture evidence. Do not infer swaps from opposing aggregate balance changes. Market observation extraction remains gated on supported route evidence and historical slot coverage.

## Alternatives

The console's newest-five probe is useful for connection diagnostics but insufficient for the selected scope. A broad parsed-provider API or continuous stream could shorten some decoding work but would introduce an unverified vendor contract, entitlement and coverage dependency. Full lifetime backfill is unnecessary for this first research sample.

## Consequences

The initial command delivers real recorded transaction history and an honest audit. It cannot yet claim to export executable replay trades or actual wallet P&L. The user must run capture and provide sanitized supported/failed examples to resolve the decoder gate. A subsequent evidence-backed ADR must record route identity and LAST_TRADE price construction before market extraction ships. Local artifacts under build output are disposable; export required evidence before cleaning the build directory.

## Revisit when

The selected history lacks a verifiable supported buy, relevant historical blocks are unavailable, a larger history/token-account scope is needed, or measured cost/coverage justifies another provider or paid entitlement.
