import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { api } from '../api/endpoints';
import type { RunStartRequest } from '../api/types';
import { SELL_CHECK_NEEDS_TRADES, SIGNAL_SIZE_NEEDS_TRADES } from '../lib/codes';
import { SAMPLE_DATASET_ID, SOL_MINT, V4_DATASET_ID } from './fixtures';
import { runPreset } from './setupHandlers';

// The backend's own messages (ConsoleConfigValidator, ConfigErrorCode), so the mock cannot drift into friendlier ones.
const DECIMAL_FORMAT = 'must be a plain decimal string such as "100" or "0.5"';
const PERCENT_FORMAT = 'must be a percent string with at most 2 decimals, such as "20" or "15.5"';
const PERCENT_RANGE = 'must be between 0.01 and 99.99 percent';
const LAMPORTS = 'must be a whole number of lamports between 0.000000001 and 1000 SOL';
const CONFIG_INVALID = {
  status: 400,
  code: 'CONFIG_INVALID',
  message: 'Simulation configuration is invalid',
};
const INVALID_PERCENT = {
  status: 400,
  code: 'INVALID_PERCENT',
  message: 'A percent setting is invalid',
};
const CONFLICTING_FIELDS = {
  status: 400,
  code: 'CONFLICTING_FIELDS',
  message: 'A percent setting disagrees with its basis point field',
};

/** How the mock answers a run start: the error's status, code, message and fields, or null when the run starts. */
async function errorOf(body: RunStartRequest) {
  try {
    await api.startRun(body);
    return null;
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;
    return { status: error.status, code: error.code, message: error.message, fields: error.fields };
  }
}

/** How the mock answers a run start: the refusal's status, code and message, or null when the run starts. */
async function refusalOf(datasetId: string, config: RunStartRequest['config']) {
  const error = await errorOf({ datasetId, config });
  return error && { status: error.status, code: error.code, message: error.message };
}

/**
 * The answer to [config] on a dataset that records trades, so a valid configuration starts. Untyped, so a test can send
 * what the console never does, such as null or a JSON number for a decimal.
 */
function configErrorOf(config: Record<string, unknown>) {
  return errorOf({ datasetId: V4_DATASET_ID, config: config as RunStartRequest['config'] });
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

  it('checks the dataset id, the preset, the configuration and then the dataset, in RunLaunchService order', async () => {
    const config = { notional: 'one' };
    expect(await errorOf({ datasetId: ' ', presetId: 'other', config })).toEqual({
      status: 400,
      code: 'INVALID_REQUEST',
      message: 'Invalid request field=datasetId reason=is required',
      fields: { datasetId: 'is required' },
    });
    expect(await errorOf({ datasetId: 'missing', presetId: 'other', config })).toEqual({
      status: 400,
      code: 'INVALID_REQUEST',
      message: 'Invalid request field=presetId reason=is not a known preset',
      fields: { presetId: 'is not a known preset' },
    });
    expect(await errorOf({ datasetId: 'missing', presetId: runPreset.id, config })).toEqual({
      ...CONFIG_INVALID,
      fields: { notional: DECIMAL_FORMAT },
    });
    expect(await errorOf({ datasetId: 'missing', presetId: runPreset.id })).toEqual({
      status: 404,
      code: 'DATASET_NOT_FOUND',
      message: 'Replay dataset not found datasetId=missing',
      fields: {},
    });
  });

  it('trims the dataset id and refuses every preset but the agreed one, even an empty id', async () => {
    expect(await errorOf({ datasetId: ` ${V4_DATASET_ID} `, presetId: runPreset.id })).toBeNull();
    expect((await errorOf({ datasetId: V4_DATASET_ID, presetId: '' }))?.fields).toEqual({
      presetId: 'is not a known preset',
    });
  });

  it.each<[unknown, string]>([
    ['0', LAMPORTS],
    ['0.0000000001', LAMPORTS],
    ['1000.000000001', LAMPORTS],
    ['1e3', DECIMAL_FORMAT],
    ['-1', DECIMAL_FORMAT],
    [1.9, DECIMAL_FORMAT],
  ])('refuses the minimum signal size %j as the backend does', async (minSignalSol, message) => {
    expect(await configErrorOf({ minSignalSol })).toEqual({
      ...CONFIG_INVALID,
      fields: { minSignalSol: message },
    });
  });

  it.each(['0.000000001', '1000', '1.9000000000', null])(
    'accepts the minimum signal size %j',
    async (minSignalSol) => {
      expect(await configErrorOf({ minSignalSol })).toBeNull();
    },
  );

  it('checks each percent level and reports a bad one as INVALID_PERCENT', async () => {
    expect(
      await configErrorOf({ dipPercent: '100', takeProfitPercent: '15.555', stopLossPercent: 40 }),
    ).toEqual({
      ...INVALID_PERCENT,
      fields: {
        dipPercent: PERCENT_RANGE,
        takeProfitPercent: PERCENT_FORMAT,
        stopLossPercent: PERCENT_FORMAT,
      },
    });
    expect(
      await configErrorOf({
        dipPercent: '0.01',
        takeProfitPercent: '99.99',
        stopLossPercent: '15.5',
      }),
    ).toBeNull();
  });

  it('reports a percent that disagrees with its basis points as CONFLICTING_FIELDS, ahead of other errors', async () => {
    expect(
      await configErrorOf({ notional: '0', dipBps: 1500, dipPercent: '20', stopLossPercent: '0' }),
    ).toEqual({
      ...CONFLICTING_FIELDS,
      fields: {
        notional: 'must be at least one lamport',
        dipPercent: 'must equal dipBps when both are sent: dipBps 1500 is 15 percent',
        stopLossPercent: PERCENT_RANGE,
      },
    });
    expect(await configErrorOf({ dipBps: 2000, dipPercent: '20.00' })).toBeNull();
    // A twin that is invalid on its own is reported alone, never also as a conflict.
    expect(await configErrorOf({ dipBps: 0, dipPercent: '20' })).toEqual({
      ...CONFIG_INVALID,
      fields: { dipBps: 'must be an integer between 1 and 9999' },
    });
  });

  it('reads a null field as absent, which keeps its default', async () => {
    expect(
      await configErrorOf({
        notional: null,
        maxAgeSlots: null,
        dipBps: null,
        dipPercent: null,
        minMarketCapUsd: null,
        transactionCostSol: null,
        quoteMint: null,
        maxHoldMs: null,
        minSignalSol: null,
      }),
    ).toBeNull();
  });

  it('limits a notional to 26 integer digits and a plain decimal to 64 digits a side', async () => {
    expect(await configErrorOf({ notional: '9'.repeat(26) })).toBeNull();
    expect(
      await configErrorOf({ notional: `1${'0'.repeat(26)}`, minSignalSol: `1.${'0'.repeat(65)}` }),
    ).toEqual({
      ...CONFIG_INVALID,
      fields: { notional: 'must have at most 26 integer digits', minSignalSol: DECIMAL_FORMAT },
    });
  });

  it('rounds the market cap and transaction cost half to even at 12 decimals before the range check', async () => {
    expect(
      await configErrorOf({
        minMarketCapUsd: '1000000000.0000000000005',
        transactionCostSol: '0.1000000000005',
      }),
    ).toBeNull();
    expect(
      await configErrorOf({
        minMarketCapUsd: '1000000000.0000000000006',
        transactionCostSol: '0.1000000000015',
      }),
    ).toEqual({
      ...CONFIG_INVALID,
      fields: {
        minMarketCapUsd: 'must be between 0 and 1000000000 US dollars',
        transactionCostSol: 'must be between 0 and 0.1 SOL',
      },
    });
  });

  it('refuses a quote mint with surrounding spaces, which the backend does not trim', async () => {
    expect(await configErrorOf({ quoteMint: SOL_MINT })).toBeNull();
    expect(await configErrorOf({ quoteMint: ` ${SOL_MINT} ` })).toEqual({
      ...CONFIG_INVALID,
      fields: { quoteMint: 'must be a 32-byte base58 address' },
    });
  });
});
