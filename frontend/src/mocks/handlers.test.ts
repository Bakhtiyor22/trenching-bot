import { File as NodeFile } from 'node:buffer';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { automationApi, type VariationConfigRequest } from '../api/automation';
import { API_BASE, ApiError, request } from '../api/client';
import { api, pairKey } from '../api/endpoints';
import type { ChartRequest, DatasetRegistration, RunStartRequest } from '../api/types';
import { SELL_CHECK_NEEDS_TRADES, SIGNAL_SIZE_NEEDS_TRADES } from '../lib/codes';
import {
  BASE_MINT,
  PAIR,
  REAL_MINT,
  SAMPLE_DATASET_ID,
  SAMPLE_RUN_ID,
  SOL_MINT,
  V4_DATASET_ID,
  WALLET,
} from './fixtures';
import { runPreset } from './setupHandlers';
import {
  createAutomationDb,
  MOCK_AGREED_CONFIG,
  MOCK_EDIT_AT,
  MOCK_LABELS,
  mockVariation,
} from './automationFixtures';
import { resetAutomationMocks } from './automationHandlers';
import { RPC_ACCOUNT_SCENARIOS, rpcAccountsFixture } from './rpcAccountFixtures';
import { variationConfigRequest } from '../features/automation/automationModel';
import { formValuesFrom } from '../features/runs/runForm';

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

/** How the mock answers [call]: the error's status, code, message and fields, or null when the call succeeds. */
async function failureOf(call: () => Promise<unknown>) {
  try {
    await call();
    return null;
  } catch (error) {
    if (!(error instanceof ApiError)) throw error;
    return { status: error.status, code: error.code, message: error.message, fields: error.fields };
  }
}

/** How the mock answers a run start: the error's status, code, message and fields, or null when the run starts. */
function errorOf(body: RunStartRequest) {
  return failureOf(() => api.startRun(body));
}

/** `InvalidConsoleRequestException` as the backend answers it. */
function invalidRequest(field: string, reason: string) {
  return {
    status: 400,
    code: 'INVALID_REQUEST',
    message: `Invalid request field=${field} reason=${reason}`,
    fields: { [field]: reason },
  };
}

/** `MethodArgumentTypeMismatchException` as the backend answers it. */
function parameterMismatch(name: string) {
  return {
    status: 400,
    code: 'INVALID_REQUEST',
    message: 'Request parameter has an invalid value',
    fields: { [name]: 'has an invalid value' },
  };
}

/** `DatasetInvalidException` as the backend answers it. */
function datasetInvalid(message: string) {
  return { status: 400, code: 'DATASET_INVALID', message, fields: {} };
}

const PAIR_FORMAT = 'must be <baseMint>/<quoteMint> with two different base58 mints';
/** Two valid mints that neither the sample dataset nor its run recorded. */
const UNRECORDED_PAIR = `${REAL_MINT}/${SOL_MINT}`;
const EARLIER = '2026-10-01T00:00:00Z';
const LATER = '2026-10-01T01:00:00.123456789Z';
const BOUNDARY = 'mock-upload-boundary';

/** One part of a multipart upload; a [filename] makes it a file part. */
interface UploadPart {
  name: string;
  filename?: string;
  content: string | Uint8Array;
}

/**
 * A dataset upload of [parts], encoded by hand: Node's fetch cannot stream a jsdom `File`, so `api.uploadDataset`
 * cannot send one here.
 */
function upload(parts: UploadPart[]) {
  const encoder = new TextEncoder();
  const chunks = parts.flatMap(({ name, filename, content }) => {
    const file = filename === undefined ? '' : `; filename="${filename}"`;
    return [
      encoder.encode(
        `--${BOUNDARY}\r\nContent-Disposition: form-data; name="${name}"${file}\r\n\r\n`,
      ),
      typeof content === 'string' ? encoder.encode(content) : content,
      encoder.encode('\r\n'),
    ];
  });
  chunks.push(encoder.encode(`--${BOUNDARY}--\r\n`));
  const body = new Uint8Array(chunks.reduce((size, chunk) => size + chunk.length, 0));
  chunks.reduce((offset, chunk) => {
    body.set(chunk, offset);
    return offset + chunk.length;
  }, 0);
  return request<DatasetRegistration>(`${API_BASE}/datasets`, {
    method: 'POST',
    headers: { 'Content-Type': `multipart/form-data; boundary=${BOUNDARY}` },
    body,
  });
}

/** A file part named [filename] holding [content]. */
function filePart(content: string | Uint8Array, filename = 'recording.json'): UploadPart {
  return { name: 'file', filename, content };
}

/** Content the mock reads as a dataset that loads: a supported schema and a well-formed checksum. */
const LOADABLE = JSON.stringify({ schemaVersion: 4, checksum: 'ab'.repeat(32) });

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

describe('mock run endpoints', () => {
  it.each<[string, () => Promise<unknown>]>([
    ['detail', () => api.run('missing')],
    ['events', () => api.runEvents('missing')],
    ['observations', () => api.runObservations('missing')],
    ['orders', () => api.runOrders('missing')],
    ['positions', () => api.runPositions('missing')],
    ['USDT view', () => api.runUsdt('missing')],
    ['report', () => request(api.runReportUrl('missing'))],
    ['chart', () => api.runChart('missing', {})],
  ])(
    'answers an unknown run on the %s endpoint as SimulationRunNotFoundException',
    async (_, call) => {
      expect(await failureOf(call)).toEqual({
        status: 404,
        code: 'RUN_NOT_FOUND',
        message: 'Simulation run not found runId=missing',
        fields: {},
      });
    },
  );
});

describe('mock chart endpoints', () => {
  const datasetChart = (request: ChartRequest, id = SAMPLE_DATASET_ID) =>
    failureOf(() => api.datasetChart(id, request));

  it('checks the parameters, then the pair a dataset chart requires, then the dataset', async () => {
    expect(await datasetChart({ maxBuckets: 0 }, 'missing')).toEqual(
      invalidRequest('maxBuckets', 'must be at least 1'),
    );
    expect(await datasetChart({}, 'missing')).toEqual(invalidRequest('pair', 'is required'));
    expect(await datasetChart({ pair: 'nope' }, 'missing')).toEqual(
      invalidRequest('pair', PAIR_FORMAT),
    );
    expect(await datasetChart({ pair: pairKey(PAIR) }, 'missing')).toEqual({
      status: 404,
      code: 'DATASET_NOT_FOUND',
      message: 'Replay dataset not found datasetId=missing',
      fields: {},
    });
  });

  it('checks the parameters, then the run, then asks for a pair when the run has several', async () => {
    const runChart = (id: string, request: ChartRequest) =>
      failureOf(() => api.runChart(id, request));
    expect(await runChart('missing', { tickLimit: -1 })).toEqual(
      invalidRequest('tickLimit', 'must be at least 0'),
    );
    expect((await runChart('missing', {}))?.code).toBe('RUN_NOT_FOUND');
    expect(await runChart(SAMPLE_RUN_ID, {})).toEqual(
      invalidRequest('pair', 'is required when the run has several pairs'),
    );
    expect(await runChart(SAMPLE_RUN_ID, { pair: pairKey(PAIR) })).toBeNull();
  });

  it('reports only the first bad parameter, in the order Spring and ChartParameters check them', async () => {
    const bad: ChartRequest = {
      pair: 'nope',
      from: 'yesterday',
      to: 'tomorrow',
      maxBuckets: 1.5,
      markerLimit: 0,
      wallet: [` ${WALLET}`],
      cursor: 'opaque',
      tickLimit: -1,
    };
    const steps: [ChartRequest, unknown][] = [
      [bad, parameterMismatch('from')],
      [{ from: LATER }, parameterMismatch('to')],
      [{ to: EARLIER }, parameterMismatch('maxBuckets')],
      [{ maxBuckets: 0 }, invalidRequest('from', 'must not be after to')],
      // WalletAddress.of does not trim.
      [{ to: LATER }, invalidRequest('wallet', 'must be a base58 wallet address')],
      [{ wallet: [WALLET, WALLET] }, invalidRequest('pair', PAIR_FORMAT)],
      [{ pair: `${BASE_MINT}/${BASE_MINT}` }, invalidRequest('pair', PAIR_FORMAT)],
      [{ pair: pairKey(PAIR) }, invalidRequest('maxBuckets', 'must be at least 1')],
      [{ maxBuckets: 1 }, invalidRequest('markerLimit', 'must be at least 1')],
      [{ markerLimit: 1 }, invalidRequest('cursor', 'is not a valid cursor')],
      [{ cursor: undefined }, invalidRequest('tickLimit', 'must be at least 0')],
      [{ tickLimit: 0 }, null],
    ];
    // Each step fixes the parameter the previous answer named, so the next bad one is reported.
    let request: ChartRequest = {};
    const answers = [];
    for (const [fix] of steps) {
      request = { ...request, ...fix };
      answers.push(await datasetChart(request));
    }
    expect(answers).toEqual(steps.map(([, answer]) => answer));
  });

  it('refuses more than 20 distinct wallets and counts a repeated one once', async () => {
    const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    const wallets = [...alphabet.slice(0, 21)].map((digit) => `${'1'.repeat(31)}${digit}`);
    expect(await datasetChart({ pair: pairKey(PAIR), wallet: wallets })).toEqual(
      invalidRequest('wallet', 'accepts at most 20 wallets'),
    );
    expect(
      await datasetChart({ pair: pairKey(PAIR), wallet: [...wallets.slice(0, 20), wallets[0]!] }),
    ).toBeNull();
  });

  it('caps a limit above its maximum instead of refusing it', async () => {
    const pair = pairKey(PAIR);
    expect(
      await api.datasetChart(SAMPLE_DATASET_ID, {
        pair,
        maxBuckets: 5000,
        markerLimit: 5000,
        tickLimit: 6000,
      }),
    ).toEqual(
      await api.datasetChart(SAMPLE_DATASET_ID, {
        pair,
        maxBuckets: 2000,
        markerLimit: 2000,
        tickLimit: 5000,
      }),
    );
  });

  it('answers a cursor past the last marker with an empty page, not an error', async () => {
    const chart = await api.datasetChart(V4_DATASET_ID, {
      pair: pairKey(PAIR),
      cursor: 'mock-chart-page:999',
    });
    expect(chart.markers.items).toEqual([]);
    expect(chart.markers.nextCursor).toBeNull();
  });

  it('answers a pair without observations with an empty chart, as the backend does', async () => {
    const dataset = await api.datasetChart(SAMPLE_DATASET_ID, { pair: UNRECORDED_PAIR });
    expect(dataset).toMatchObject({
      range: { from: null, to: null },
      extent: null,
      candles: [],
      markers: { total: 0, items: [] },
      paperFills: [],
      ticks: { total: 0, items: [] },
    });
    const run = await api.runChart(SAMPLE_RUN_ID, {
      pair: UNRECORDED_PAIR,
      from: EARLIER,
      to: LATER,
    });
    expect(run).toMatchObject({ range: { from: EARLIER, to: LATER }, candles: [], paperFills: [] });
  });
});

describe('mock dataset endpoints', () => {
  it('reads the observations pair before the dataset', async () => {
    expect(await failureOf(() => api.datasetObservations('missing', 'nope'))).toEqual(
      invalidRequest('pair', PAIR_FORMAT),
    );
    expect((await failureOf(() => api.datasetObservations('missing', pairKey(PAIR))))?.code).toBe(
      'DATASET_NOT_FOUND',
    );
    expect(await api.datasetObservations(SAMPLE_DATASET_ID, UNRECORDED_PAIR)).toEqual([]);
  });

  it('imports the sample by its name trimmed and in any case, and names an unknown one', async () => {
    expect((await api.importSample(' DIP_SAMPLE_V3 ')).dataset.id).toBe(SAMPLE_DATASET_ID);
    expect(await failureOf(() => api.importSample('dip-sample-v9'))).toEqual({
      status: 404,
      code: 'SAMPLE_NOT_FOUND',
      message: 'Unknown sample dataset sample=dip-sample-v9',
      fields: {},
    });
  });
});

describe('mock POST /api/datasets', () => {
  // Node's fetch builds each parsed file part with the global File, and jsdom's replacement fails its own check.
  beforeEach(() => vi.stubGlobal('File', NodeFile));
  afterEach(() => vi.unstubAllGlobals());

  it('refuses an upload that is not multipart or has no file part, as Spring does', async () => {
    expect(
      await failureOf(() =>
        request(`${API_BASE}/datasets`, {
          method: 'POST',
          headers: { 'Content-Type': `multipart/form-data; boundary=${BOUNDARY}` },
          body: 'no parts',
        }),
      ),
    ).toEqual({
      status: 400,
      code: 'INVALID_REQUEST',
      message: 'Request must be multipart/form-data with a file part',
      fields: {},
    });
    const missing = {
      status: 400,
      code: 'INVALID_REQUEST',
      message: 'Multipart request is missing a required part',
      fields: { file: 'is required' },
    };
    expect(await failureOf(() => upload([{ name: 'name', content: 'x' }]))).toEqual(missing);
    // A part without a file name is a form field, not a file.
    expect(await failureOf(() => upload([{ name: 'file', content: LOADABLE }]))).toEqual(missing);
  });

  const longName = 'n'.repeat(129);
  it.each<[string, UploadPart[], string]>([
    // An empty file is refused before its name is read.
    [
      'an empty file',
      [filePart(''), { name: 'name', content: longName }],
      'Uploaded dataset file is empty',
    ],
    // The name is checked before the content is read.
    [
      'a long name',
      [filePart('not json'), { name: 'name', content: longName }],
      'Dataset name must be 1..128 characters',
    ],
    [
      'a long file name',
      [filePart(LOADABLE, longName), { name: 'name', content: ' ' }],
      'Dataset name must be 1..128 characters',
    ],
    ['a NUL byte', [filePart(`${LOADABLE}\u0000`)], 'Dataset must be UTF-8 JSON'],
    [
      'malformed UTF-8',
      [filePart(new Uint8Array([0x7b, 0xc3, 0x28, 0x7d]))],
      'Dataset must be UTF-8 JSON',
    ],
    ['text that is not JSON', [filePart('not json')], 'Replay dataset is not valid JSON'],
    ['JSON that is not an object', [filePart('[4]')], 'Expected integer field=schemaVersion'],
    [
      'a schema version string',
      [filePart('{"schemaVersion":"4"}')],
      'Expected integer field=schemaVersion',
    ],
    [
      'an unsupported schema version',
      [filePart('{"schemaVersion":7}')],
      'Unsupported replay schemaVersion=7; supported versions are [3, 4, 5, 6] (UNSUPPORTED_SCHEMA)',
    ],
    ['no checksum', [filePart('{"schemaVersion":4}')], 'Expected nonempty string field=checksum'],
    [
      'a checksum that does not match',
      [filePart(JSON.stringify({ schemaVersion: 4, checksum: '0'.repeat(64) }))],
      `Replay checksum mismatch computed=${SAMPLE_DATASET_ID}`,
    ],
  ])('refuses %s with the catalog message', async (_, parts, message) => {
    expect(await failureOf(() => upload(parts))).toEqual(datasetInvalid(message));
  });

  it('names a dataset uploaded without a name after its file, extension and all', async () => {
    const registration = await upload([filePart(LOADABLE, ' my-recording.json ')]);
    expect(registration.created).toBe(true);
    expect(registration.dataset.name).toBe('my-recording.json');
  });
});

describe('mock GET /api/config/defaults', () => {
  it('sends each level with its percent twin and percent range, as ConfigResponseMapper does', async () => {
    const { defaults, limits } = await api.configDefaults();
    expect([defaults.dipPercent, defaults.takeProfitPercent, defaults.stopLossPercent]).toEqual([
      '15',
      '30',
      '15',
    ]);
    const range = { min: '0.01', max: '99.99' };
    expect([limits.dipPercent, limits.takeProfitPercent, limits.stopLossPercent]).toEqual([
      range,
      range,
      range,
    ]);
  });
});

describe('mock POST /api/solana/address-check', () => {
  it.each([
    ['1'.repeat(31), 'Invalid base58 length'],
    ['0'.repeat(44), 'Invalid base58 character'],
    ['z'.repeat(44), 'Invalid decoded base58 length'],
  ])('refuses %s with the Base58 reason', async (address, reason) => {
    expect(await failureOf(() => api.addressCheck(address))).toEqual({
      status: 400,
      code: 'INVALID_ADDRESS',
      message: 'Not a valid 32-byte base58 Solana address',
      fields: { address: reason },
    });
  });

  it('trims the address and checks it before the RPC configuration', async () => {
    expect(await failureOf(() => api.addressCheck(` ${WALLET} `))).toEqual({
      status: 409,
      code: 'SOLANA_NOT_CONFIGURED',
      message: 'Solana RPC is not configured; set SOLANA_RPC_URL',
      fields: {},
    });
  });
});

describe('mock GET /api/solana/accounts', () => {
  const FIELDS = [
    'balanceCheckedAt',
    'balanceProblem',
    'configured',
    'creditsLimit',
    'creditsRemaining',
    'creditsUsed',
    'cycleEndsOn',
    'host',
    'name',
    'outOfCreditsSince',
    'plan',
    'state',
  ];

  it('answers both accounts as not configured without an RPC, with no account active', async () => {
    const answer = await api.solanaAccounts();
    expect(answer.active).toBeNull();
    expect(answer.accounts.map((a) => [a.name, a.configured, a.state])).toEqual([
      ['primary', false, 'NOT_CONFIGURED'],
      ['backup', false, 'NOT_CONFIGURED'],
    ]);
  });

  it.each(RPC_ACCOUNT_SCENARIOS)(
    'keeps the contract of RpcAccountsResponse in the %s state',
    (scenario) => {
      const answer = rpcAccountsFixture(scenario);
      // Exactly the DTO's fields: never a URL, a key or a project id.
      for (const account of answer.accounts) expect(Object.keys(account).sort()).toEqual(FIELDS);
      expect(JSON.stringify(answer)).not.toMatch(/https?:|api-key/i);
      // `active` names the one account whose state is ACTIVE, or is null when there is none.
      const active = answer.accounts.filter((a) => a.state === 'ACTIVE').map((a) => a.name);
      expect(active).toEqual(answer.active ? [answer.active] : []);
      for (const account of answer.accounts) {
        expect(account.configured).toBe(account.state !== 'NOT_CONFIGURED');
        // An account that is out of credits says since when; no other state does.
        expect(account.outOfCreditsSince != null).toBe(account.state === 'OUT_OF_CREDITS');
      }
    },
  );
});

describe('mock variation endpoints', () => {
  const v04 = () => automationApi.variation('V04');
  const editOf = async (label: string, change: Partial<VariationConfigRequest>) => {
    const current = await automationApi.variation(label);
    return {
      requestId: `ui-edit-${crypto.randomUUID()}`,
      config: { ...variationConfigRequest(formValuesFrom(current.config, null)), ...change },
    };
  };

  it('lists the variations by sequence, filtered by state, and refuses an unknown state', async () => {
    const all = await automationApi.variations(null);
    expect(all.planId).toBe('decu-sweep-1');
    expect(all.items.map((v) => v.label)).toEqual([...MOCK_LABELS, 'V10']);
    const archived = await automationApi.variations('ARCHIVED');
    expect(archived.items.map((v) => v.label)).toEqual(['V07']);
    expect(archived.items[0]).toMatchObject({ replacedByLabel: 'V10', archivedAt: MOCK_EDIT_AT });
    expect((await automationApi.variations('ACTIVE')).items).toHaveLength(10);
    expect(
      await failureOf(() =>
        request(`${API_BASE}/automation/variations?state=GONE`).then(() => null),
      ),
    ).toMatchObject({
      status: 400,
      code: 'INVALID_REQUEST',
      fields: { state: expect.any(String) },
    });
  });

  it('reads one variation, or 404 for a label that never existed', async () => {
    expect(await v04()).toMatchObject({
      label: 'V04',
      state: 'ACTIVE',
      differsFromAgreed: ['takeProfitBps'],
      config: { takeProfitBps: 8000, takeProfitPercent: '80' },
    });
    expect(await failureOf(() => automationApi.variation('V99'))).toMatchObject({
      status: 404,
      code: 'AUTOMATION_VARIATION_NOT_FOUND',
    });
  });

  it('archives and creates on an edit (201), and answers a repeat with the same variation (200)', async () => {
    const body = await editOf('V04', { takeProfitPercent: '80', stopLossPercent: '50' });
    const first = await automationApi.editVariation('V04', body);
    expect(first.created).toBe(true);
    expect(first.variation).toMatchObject({
      label: 'V11',
      state: 'ACTIVE',
      replacesLabel: 'V04',
      changedFromReplaced: ['stopLossBps'],
      differsFromAgreed: ['takeProfitBps', 'stopLossBps'],
      config: { takeProfitBps: 8000, stopLossBps: 5000, stopLossPercent: '50' },
    });
    expect(first.variation.activeFrom).not.toBeNull();
    expect(await v04()).toMatchObject({ state: 'ARCHIVED', replacedByLabel: 'V11' });
    const repeat = await automationApi.editVariation('V04', body);
    expect(repeat).toEqual({ variation: first.variation, created: false });
    expect((await automationApi.variations(null)).items).toHaveLength(12);
    // The archived variation keeps its runs.
    const runs = await automationApi.sweepRuns({ label: 'V04', page: 0, size: 100 });
    expect(runs.total).toBeGreaterThan(0);
  });

  it('refuses every case of the contract with its code', async () => {
    const refusal = async (label: string, change: Partial<VariationConfigRequest>) =>
      failureOf(async () => automationApi.editVariation(label, await editOf(label, change)));
    expect(await refusal('V07', { stopLossPercent: '50' })).toMatchObject({
      status: 409,
      code: 'VARIATION_ARCHIVED',
    });
    expect(await refusal('V00', { stopLossPercent: '50' })).toMatchObject({
      status: 409,
      code: 'VARIATION_BASELINE',
    });
    expect(await refusal('V04', {})).toMatchObject({ status: 409, code: 'VARIATION_UNCHANGED' });
    expect(await refusal('V04', { takeProfitPercent: '35' })).toMatchObject({
      status: 409,
      code: 'VARIATION_DUPLICATE',
      fields: { label: 'V03' },
    });
    expect(await refusal('V04', { minSignalSol: null })).toMatchObject({
      status: 400,
      code: 'CONFIG_INVALID',
      fields: { minSignalSol: 'must be set' },
    });
    expect(await refusal('V04', { minSignalSol: '1.2' })).toMatchObject({
      status: 400,
      code: 'CONFIG_INVALID',
      fields: { minSignalSol: expect.stringMatching(/capture filter/) },
    });
    expect(await refusal('V04', { stopLossPercent: '80.555' })).toMatchObject({
      status: 400,
      code: 'INVALID_PERCENT',
    });
    const missing = await editOf('V04', {});
    delete (missing.config as Record<string, unknown>).latencySlots;
    expect(await failureOf(() => automationApi.editVariation('V04', missing))).toMatchObject({
      status: 400,
      code: 'CONFIG_INVALID',
      fields: { latencySlots: 'must be set' },
    });
    expect(
      await failureOf(async () =>
        automationApi.editVariation('V04', { ...(await editOf('V04', {})), requestId: ' ' }),
      ),
    ).toMatchObject({
      status: 400,
      code: 'INVALID_REQUEST',
      fields: { requestId: expect.any(String) },
    });
    expect(
      await failureOf(async () => automationApi.editVariation('V99', await editOf('V04', {}))),
    ).toMatchObject({
      status: 404,
      code: 'AUTOMATION_VARIATION_NOT_FOUND',
    });
    // A plan that already numbers 500 variations stores no further edit.
    const seeded = (await automationApi.variations(null)).items.length;
    resetAutomationMocks({
      ...createAutomationDb(),
      variations: [
        ...createAutomationDb().variations,
        ...Array.from({ length: 500 - seeded }, (_, i) =>
          mockVariation(`V${seeded + i}`, MOCK_AGREED_CONFIG, { state: 'ARCHIVED' }),
        ),
      ],
    });
    expect(await refusal('V04', { stopLossPercent: '50' })).toMatchObject({
      status: 409,
      code: 'VARIATION_LIMIT_REACHED',
    });
    expect((await automationApi.variations(null)).items).toHaveLength(500);
    resetAutomationMocks();
    // Nothing was stored by any refusal.
    expect((await automationApi.variations(null)).items).toHaveLength(11);
  });
});

describe('mock GET /api/automation/sweep-runs', () => {
  it('filters by plan and by block time, from inclusive and to exclusive', async () => {
    const from = '2026-10-29T07:00:00.000Z';
    const to = '2026-10-30T07:00:00.000Z';
    const page = await automationApi.sweepRuns({
      planId: 'decu-sweep-1',
      from,
      to,
      page: 0,
      size: 100,
    });
    expect(page.total).toBeGreaterThan(0);
    for (const row of page.items) {
      expect(Date.parse(row.blockTime!)).toBeGreaterThanOrEqual(Date.parse(from));
      expect(Date.parse(row.blockTime!)).toBeLessThan(Date.parse(to));
    }
    expect(
      (await automationApi.sweepRuns({ planId: 'decu-sweep-2', page: 0, size: 10 })).total,
    ).toBe(0);
  });

  it('runs a replaced variation only on buys before its edit, and its replacement only after', async () => {
    const before = await automationApi.sweepRuns({ label: 'V07', page: 0, size: 100 });
    const after = await automationApi.sweepRuns({ label: 'V10', page: 0, size: 100 });
    expect(before.items.every((r) => Date.parse(r.blockTime!) < Date.parse(MOCK_EDIT_AT))).toBe(
      true,
    );
    expect(after.items.every((r) => Date.parse(r.blockTime!) >= Date.parse(MOCK_EDIT_AT))).toBe(
      true,
    );
    expect(after.total).toBeGreaterThan(0);
  });

  it('refuses a from that is not before to', async () => {
    const at = '2026-10-29T07:00:00.000Z';
    expect(
      await failureOf(() => automationApi.sweepRuns({ from: at, to: at, page: 0, size: 10 })),
    ).toMatchObject({
      status: 400,
      code: 'INVALID_REQUEST',
      fields: { from: 'must be before to' },
    });
  });
});

describe('mock GET /api/runs count only', () => {
  it('answers size 0 with no rows and the usual total, counts and totals', async () => {
    const page = await api.runs({ page: 0, size: 0 });
    expect(page.items).toEqual([]);
    expect(page.size).toBe(0);
    expect(page.total).toBeGreaterThan(0);
    expect(page.statusCounts).not.toBeNull();
    expect(page.totals?.length).toBeGreaterThan(0);
  });
});
