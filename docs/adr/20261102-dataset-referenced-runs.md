# Dataset-referenced runs

Accepted 2026-11-02 with the approved [automated capture and sweep plan](../plans/archive/20261102-decu-capture-sweep-automation.md) (Key Decision 6, Module Contracts → `replay`, Data Model → "Changed: simulation_runs"). It amends the [configurable replay runs decision](20261002-configurable-replay-runs.md) and the dataset delete rule of the [delete plan](../plans/archive/20261010-delete-captures-and-datasets.md): runs no longer always keep their own copy of the dataset.

## Context

Every stored run copies its inputs. `SimulationRunTransaction` writes the whole dataset JSON into `simulation_runs.dataset_snapshot` and copies every market observation into `market_observations` under the run id. A captured dataset is 0.3–2.5 MB of JSON plus thousands of observation rows, and the copy is the same for every run of that dataset: only the configuration differs.

Automation will replay a fixed sweep of up to 10 configurations on every captured buy, and the user chose to keep everything. Copying would store each dataset up to ten more times. The catalog already holds the dataset once, keyed by its checksum, and a run already records that checksum.

## Decision

1. **Two storages, chosen at start.** `replay.domain.RunStorage` is `COPY` or `DATASET_REFERENCE`. `SimulationRuns.start(datasetId, config, storage = COPY)`. A `DATASET_REFERENCE` run stores a null `dataset_snapshot` and no `market_observations` rows; its wallet events, intents, orders, fills and positions are stored under the run as before, since they carry the run's results and provenance.
2. **Storage is not part of identity.** The run id stays the hash of engine version, dataset checksum and configuration hash, and the result checksum is unchanged, so the same dataset and configuration give the same run either way. A repeat start returns the run as first stored, whatever storage it asks for.
3. **Inputs are read through one port.** `replay.RunInputs` returns a run's market observations and wallet transactions: a copy run's stored copies, or a reference run's catalog dataset (`ReplayDatasetCatalog.load(datasetChecksum)`) in the same canonical order (`OrderedMarketData.canonical`, `OrderedWalletTransactions.canonical`). Both read the same for the same dataset. The console's run observations, events, chart and USDT views read through it.
4. **A dataset in use cannot be deleted.** `ReplayDatasetCatalog.delete` throws `DatasetInUseException(datasetId, runCount)` while `DATASET_REFERENCE` runs read the dataset, and removes nothing; the console answers 409 `DATASET_IN_USE` with the count in the message. Copy runs never block a delete. The delete removes the row first and then counts the reference runs in the same transaction, and a reference run share-locks the dataset row while it is stored, so a delete and a concurrent reference run cannot leave a run without its dataset: whichever commits second sees the other.
5. **Only automation asks for references.** Nothing passes `DATASET_REFERENCE` until the sweep does; `POST /api/runs` keeps `COPY` and has no storage field. Run summaries and run detail expose `storage` additively.
6. **Schema.** `simulation_runs` gains the nullable `storage varchar(20)` (null, on every row stored earlier, reads as `COPY`; no backfill), `dataset_snapshot` becomes nullable, and the index `idx_simulation_runs_storage_dataset (dataset_checksum, storage)` serves the delete check. Production adds the CHECK `ck_simulation_runs_storage` (a snapshot exactly when the run is a copy) by hand ([V20261102120000](../schema/V20261102120000__automation.sql)).

## Alternatives

- **Keep copying every run.** Simplest, and every run stays self-contained after its dataset is deleted. Rejected: a ten-entry sweep multiplies each dataset's storage by ten for data that never differs between the runs.
- **Persist fewer runs** (store only the sweep's summary numbers, or only the best entry). Rejected: every sweep run must open at `/api/runs/{id}` with its scenarios, orders and positions, so its numbers can be rebuilt by hand, and the user chose to keep everything.
- **Drop the copy for every run.** Rejected for now: existing runs and console runs rely on surviving a dataset delete, and the copy path is proven. It stays the default.

## Consequences

- A reference run depends on its catalog dataset; deleting that dataset is refused while such runs exist. To delete it, the reference runs must go first (there is no run delete yet).
- Console reads of a run's inputs go through `RunInputs`; a new reader must use it too, never `MarketObservationStore.observations(runId)` or a run's `dataset_snapshot`, which a reference run does not have.
- Reading a reference run's inputs parses its dataset JSON (up to the catalog's upload limit) on each request instead of reading indexed rows.
- The copy path is unchanged: copy runs keep their snapshot and observations, and their delete behaviour is as before.
- A local database Hibernate created keeps `dataset_snapshot NOT NULL` (`update` never relaxes a column) and needs the `DROP NOT NULL` once, or a reset, before it can store a reference run.
- Restoring `dataset_snapshot NOT NULL` later requires deleting or re-running the reference runs as copies first.

## Revisit when

- Reading reference runs' inputs becomes a measurable cost in the console (parsing the dataset per request), which would call for caching the parsed dataset or storing observations once per dataset.
- A run delete or dataset retention policy is needed, which must handle reference runs first.
- Copy runs should stop copying too, which would make `RunInputs` the only path and let the observation copy be dropped.
