# Architecture diagrams

Editable draw.io diagrams for the architecture handbook (snapshot 2026-10-03). Open them in
[diagrams.net](https://app.diagrams.net) or the draw.io IDE plugin.

| File | Shows |
|---|---|
| `01-system-context.drawio` | Users and external systems |
| `02-high-level-architecture.drawio` | Runtime processes: browser, Spring app, CLI tasks, database, files, RPC |
| `03-module-dependencies.drawio` | Package imports between feature modules |
| `04-flow-dataset-registration.drawio` | Dataset upload validation and idempotent insert |
| `05-flow-simulation-engine.drawio` | `ReplayRunner`: normalize, intents, fills, positions, checksums |
| `06-flow-wallet-history-capture.drawio` | `captureWalletHistory` discovery, detail fetch, checkpoints |
| `07-seq-run-start.drawio` | Console run start, idempotent return, concurrent-writer recovery |
| `08-seq-wallet-capture.drawio` | Capture against the Solana RPC provider |
| `09-seq-inspect-decode.drawio` | Offline verification and Pump.fun decoding |
| `10-state-scenario-lifecycle.drawio` | Intent, order and position states |
| `11-state-capture-record.drawio` | Per-signature capture and decode outcomes |
| `12-state-capture-manifest.drawio` | Capture directory lifecycle |
| `13-class-simulation-core.drawio` | Simulation engine classes |
| `14-class-ports-persistence.drawio` | Replay ports, run ledger, feature store ports |
| `15-class-wallet-capture.drawio` | Capture, artifact store, evidence and decoder classes |
| `16-erd.drawio` | Tables, keys and logical links |
| `17-integrations.drawio` | APIs, database, RPC, files, metrics, configuration |
