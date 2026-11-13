/**
 * The first replay dataset schema whose market observations carry their decoded trade, so who traded is known
 * (the backend's `ReplayDataset.SCHEMA_V4`). Schema 3 recordings carry prices and curve states only.
 */
export const TRADES_SCHEMA_VERSION = 4;

/** Whether a dataset of [schemaVersion] records who traded (the backend's `ReplayDataset.recordsTrades`). */
export function recordsTrades(schemaVersion: number): boolean {
  return schemaVersion >= TRADES_SCHEMA_VERSION;
}
