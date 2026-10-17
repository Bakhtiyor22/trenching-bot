import { http, HttpResponse } from 'msw';
import type { Recommendation, RunPreset, UsageReport } from '../api/setup';
import type { RunDetail } from '../api/types';
import { bpsToPercent } from '../lib/percent';
import { configDefaults, BASE_MINT, SAMPLE_DATASET_ID, sampleDataset, WALLET } from './fixtures';

export const runPreset: RunPreset = {
  id: 'decu-dip-v2-on-dip-entry-v1',
  source: {
    document: 'docs/strategy/decu-dip-v2-spec.md',
    strategy: 'decu-dip',
    version: 2,
    updated: '2026-10-11',
  },
  engine: 'dip-entry v1',
  config: {
    ...configDefaults.defaults,
    dipBps: 2000,
    dipPercent: '20',
    takeProfitBps: 5500,
    takeProfitPercent: '55',
    stopLossBps: 4000,
    stopLossPercent: '40',
    notional: '0.100000000000',
    minMarketCapUsd: '10000.000000000000',
  },
  fields: [
    'dipBps',
    'takeProfitBps',
    'stopLossBps',
    'notional',
    'transactionCostSol',
    'quoteMint',
    'minMarketCapUsd',
    'latencySlots',
    'horizonSlots',
    'maxAgeSlots',
  ].map((field) => ({
    field,
    status:
      field === 'horizonSlots'
        ? 'MISSING'
        : field === 'minMarketCapUsd'
          ? 'AGREED_MEASURED_DIFFERENTLY'
          : field === 'latencySlots'
            ? 'ASSUMPTION'
            : field === 'maxAgeSlots'
              ? 'ENGINE_DEFAULT'
              : 'AGREED',
    specRef: null,
    noteCode: field === 'horizonSlots' ? 'NO_SINGLE_WINDOW_IN_SPEC' : null,
  })),
  unsupportedRules: [
    'FIRST_EVER_BUY',
    'SIGNAL_OVER_1_9_SOL',
    'MCAP_AT_REFERENCE_BUY',
    'DIP_WINDOW_30_SECONDS',
    'ENTRY_SLIPPAGE_15_PERCENT',
    'TIMED_EXIT',
    'EXIT_RETRIES_AND_UNRESOLVED',
    'ONE_POSITION_INCLUDING_IN_FLIGHT',
  ].map((code, index) => ({ specRef: `P${index + 1}`, code })),
};
export function withRunSetup(detail: RunDetail): RunDetail {
  const time = (slot: number) =>
    new Date(Date.parse('2026-10-01T00:00:00Z') + (slot - 1000) * 400).toISOString();
  return {
    ...detail,
    source: {
      datasetId: detail.run.datasetId,
      datasetName: sampleDataset.name,
      captureId: 'capture-demo',
      captureDisplayName: 'First wallet sample',
      caseId: null,
    },
    config: {
      ...detail.config,
      dipPercent: bpsToPercent(detail.config.dipBps),
      takeProfitPercent: bpsToPercent(detail.config.takeProfitBps),
      stopLossPercent: bpsToPercent(detail.config.stopLossBps),
    },
    scenarios: detail.scenarios.map((scenario) => ({
      ...scenario,
      timeline: {
        firstBuyAt: time(scenario.sourceSlot),
        dipAt: scenario.trigger ? time(scenario.trigger.slot) : null,
        entryAt: scenario.fill ? time(scenario.fill.actualSlot) : null,
        exitTriggerAt: scenario.position?.exitTrigger
          ? time(scenario.position.exitTrigger.slot)
          : null,
        exitAt: scenario.position?.close ? time(scenario.position.close.slot) : null,
      },
    })),
  };
}
export function createSetupHandlers(wait: () => Promise<void>) {
  return [
    http.get('/api/runs/preset', async () => {
      await wait();
      return HttpResponse.json(runPreset);
    }),
    http.get('/api/runs/recommendations', async () => {
      await wait();
      return HttpResponse.json<Recommendation[]>([
        {
          id: 'USE_AGREED_STRATEGY',
          appliesTo: 'RUN',
          setting: { field: 'presetId', value: runPreset.id },
          facts: { unsupportedRuleCount: 8, missingFieldCount: 1 },
          caveatCode: null,
        },
        {
          id: 'ADD_LEGACY_COMPARISON',
          appliesTo: 'RUN',
          setting: null,
          facts: { dipPercent: '15', takeProfitPercent: '30', stopLossPercent: '15' },
          caveatCode: null,
        },
      ]);
    }),
    http.get('/api/captures/recommendations', async () => {
      await wait();
      return HttpResponse.json<Recommendation[]>([
        {
          id: 'SCOPE_WALLET_TOKEN',
          appliesTo: 'CAPTURE',
          setting: { field: 'scope', value: 'wallet-token' },
          facts: { limit: 1000, scanPages: 20 },
          caveatCode: null,
        },
        {
          id: 'CUTOFF_DECIDED',
          appliesTo: 'CAPTURE',
          setting: { field: 'cutoffMode', value: 'DECIDED' },
          facts: {},
          caveatCode: 'CUTOFF_USES_LEGACY_DEFAULTS',
        },
        {
          id: 'ENABLE_RECOVERY',
          appliesTo: 'CAPTURE',
          setting: { field: 'recovery', value: 'ENABLED' },
          facts: { requests: 1200, attempts: 3 },
          caveatCode: null,
        },
      ]);
    }),
    http.get('/api/wallets/:address/activity', async ({ params, request }) => {
      await wait();
      const url = new URL(request.url);
      return HttpResponse.json({
        wallet: String(params.address),
        source: 'pumpfun-user-volume-accumulator',
        actions: url.searchParams.has('before')
          ? []
          : [
              {
                signature: '2'.repeat(64),
                instructionIndex: 0,
                innerIndex: null,
                slot: 1000,
                blockTime: '2026-10-16T12:00:00Z',
                side: 'BUY',
                mint: BASE_MINT,
                solLamports: '2000000000',
                tokenRaw: '100000000',
                tokenDecimals: 6,
                venue: 'PUMPFUN_CURVE',
                earlierActionInPage: false,
                overSignalThreshold: true,
                signalBasis: 'CURVE_PAYMENT_EXCLUDING_FEES',
                capturedIn: [],
              },
              {
                signature: '3'.repeat(64),
                instructionIndex: 1,
                innerIndex: null,
                slot: 999,
                blockTime: '2026-10-16T11:59:00Z',
                side: 'SELL',
                mint: BASE_MINT,
                solLamports: '1000000000',
                tokenRaw: '100000000',
                tokenDecimals: 6,
                venue: 'PUMPFUN_CURVE',
                earlierActionInPage: false,
                overSignalThreshold: false,
                signalBasis: 'CURVE_PAYMENT_EXCLUDING_FEES',
                capturedIn: [],
              },
            ],
        unreadable: 1,
        scanned: 3,
        nextBefore: url.searchParams.has('before') ? null : '3'.repeat(64),
        requestsUsed: 3,
        cachedAt: null,
      });
    }),
    http.get('/api/usage', async ({ request }) => {
      await wait();
      const period = new URL(request.url).searchParams.get('period');
      const hours = period === 'LAST_24_HOURS' ? 24 : period === 'LAST_7_DAYS' ? 168 : 1;
      const to = new Date();
      const from = new Date(to.getTime() - hours * 3600_000);
      const totals = {
        requests: 128,
        failedRequests: 2,
        rateLimitedResponses: 1,
        responseBytes: 2_500_000,
        cacheHits: 23,
      };
      return HttpResponse.json<UsageReport>({
        from: from.toISOString(),
        to: to.toISOString(),
        bucketSeconds: 300,
        recordingStartedAt: from.toISOString(),
        totals,
        byCall: [
          {
            provider: 'SOLANA_RPC',
            operation: 'getTransaction',
            purpose: 'READ_TRANSACTION',
            totals,
            sharePercent: '100',
            lastCallAt: to.toISOString(),
          },
        ],
        byTask: [{ task: 'MARKET_STEP', totals }],
        series: Array.from({ length: 12 }, (_, index) => ({
          bucketStart: new Date(from.getTime() + index * 300_000).toISOString(),
          provider: 'SOLANA_RPC',
          requests: index + 1,
        })),
        pacing: [
          { provider: 'SOLANA_RPC', minRequestIntervalMillis: 125, maxConcurrentRequests: 4 },
        ],
      });
    }),
  ];
}
export const captureSuggestion = {
  label: 'Decu',
  address: WALLET,
  reasonCode: 'AGREED_STRATEGY_WALLET',
};
export const datasetSource = {
  datasetId: SAMPLE_DATASET_ID,
  datasetName: sampleDataset.name,
  captureId: 'capture-demo',
  captureDisplayName: 'First wallet sample',
  caseId: null,
};
