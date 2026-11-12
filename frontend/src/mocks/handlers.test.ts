import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { RunStartRequest } from '../api/types';
import { SELL_CHECK_NEEDS_TRADES, SIGNAL_SIZE_NEEDS_TRADES } from '../lib/codes';
import { SAMPLE_DATASET_ID, V4_DATASET_ID } from './fixtures';

/** How the mock answers a run start: the refusal's status, code and message, or null when the run starts. */
async function refusalOf(datasetId: string, config: RunStartRequest['config']) {
  try {
    await api.startRun({ datasetId, config });
    return null;
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;
    return { status: error.status, code: error.code, message: error.message };
  }
}

describe('mock POST /api/runs', () => {
  it('refuses the trade rules on a schema 3 dataset as ReplayRunner does: the sell rule first, then the signal size', async () => {
    expect(
      await refusalOf(SAMPLE_DATASET_ID, { followWalletSells: true, minSignalSol: '1.9' }),
    ).toEqual({
      status: 409,
      code: SELL_CHECK_NEEDS_TRADES,
      message:
        'The wallet-sell rule needs a dataset whose observations record trades schemaVersion=3 minimumSchemaVersion=4 (SELL_CHECK_NEEDS_TRADES)',
    });
    expect(
      await refusalOf(SAMPLE_DATASET_ID, { followWalletSells: false, minSignalSol: '1.9' }),
    ).toEqual({
      status: 409,
      code: SIGNAL_SIZE_NEEDS_TRADES,
      message:
        'The minimum signal size needs a dataset whose observations record trades schemaVersion=3 minimumSchemaVersion=4 (SIGNAL_SIZE_NEEDS_TRADES)',
    });
    expect(
      await refusalOf(SAMPLE_DATASET_ID, { followWalletSells: false, minSignalSol: null }),
    ).toBeNull();
    expect(await refusalOf(SAMPLE_DATASET_ID, undefined)).toBeNull();
  });

  it('runs both trade rules on a dataset that records trades', async () => {
    expect(
      await refusalOf(V4_DATASET_ID, { followWalletSells: true, minSignalSol: '1.9' }),
    ).toBeNull();
  });
});
