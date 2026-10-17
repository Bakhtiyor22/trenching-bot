import { API_BASE, getJson, withQuery } from './client';
import type { Config, IsoInstant } from './types';

export interface Recommendation {
  id: string;
  appliesTo: 'CAPTURE' | 'RUN';
  setting: { field: string; value: string } | null;
  facts: Record<string, string | number>;
  caveatCode: string | null;
}
export type PresetFieldStatus =
  'AGREED' | 'AGREED_MEASURED_DIFFERENTLY' | 'ASSUMPTION' | 'ENGINE_DEFAULT' | 'MISSING';
export interface RunPreset {
  id: string;
  source: { document: string; strategy: string; version: number; updated: string };
  engine: string;
  config: Config;
  fields: Array<{
    field: string;
    status: PresetFieldStatus;
    specRef: string | null;
    noteCode: string | null;
  }>;
  unsupportedRules: Array<{ specRef: string; code: string }>;
}
export type UsageProvider = 'SOLANA_RPC' | 'BINANCE' | 'TELEGRAM';
export type UsagePurpose =
  | 'READ_TRANSACTION'
  | 'LIST_SIGNATURES'
  | 'MAINNET_CHECK'
  | 'MINT_PROGRAM_CHECK'
  | 'CONNECTION_CHECK'
  | 'MINUTE_RATES'
  | 'LIVE_RATE'
  | 'UNKNOWN';
export type UsageTask =
  | 'WALLET_STEP'
  | 'MARKET_STEP'
  | 'RATES_STEP'
  | 'CASE_RATES'
  | 'WALLET_ACTIVITY'
  | 'CONNECTION_CHECK'
  | 'LIVE_PRICE'
  | 'UNATTRIBUTED';
export type UsagePeriod = 'LAST_HOUR' | 'LAST_24_HOURS' | 'LAST_7_DAYS' | 'ALL';
export interface UsageTotals {
  requests: number;
  failedRequests: number;
  rateLimitedResponses: number;
  responseBytes: number;
  cacheHits: number;
}
export interface UsagePoint {
  bucketStart: IsoInstant;
  provider: UsageProvider;
  requests: number;
}
export interface UsageReport {
  from: IsoInstant;
  to: IsoInstant;
  bucketSeconds: number;
  recordingStartedAt: IsoInstant | null;
  totals: UsageTotals;
  byCall: Array<{
    provider: UsageProvider;
    operation: string;
    purpose: UsagePurpose;
    totals: UsageTotals;
    sharePercent: string;
    lastCallAt: IsoInstant | null;
  }>;
  byTask: Array<{ task: UsageTask; totals: UsageTotals }>;
  series: UsagePoint[];
  pacing: Array<{
    provider: UsageProvider;
    minRequestIntervalMillis: number;
    maxConcurrentRequests: number;
  }>;
}
export interface WalletAction {
  signature: string;
  instructionIndex: number;
  innerIndex: number | null;
  slot: number;
  blockTime: IsoInstant | null;
  side: 'BUY' | 'SELL';
  mint: string;
  solLamports: string;
  tokenRaw: string;
  tokenDecimals: number;
  venue: string;
  earlierActionInPage: boolean;
  overSignalThreshold: boolean;
  signalBasis: string;
  capturedIn: Array<{ captureId: string; displayName: string }>;
}
export interface WalletActivity {
  wallet: string;
  source: string;
  actions: WalletAction[];
  unreadable: number;
  scanned: number;
  nextBefore: string | null;
  requestsUsed: number;
  cachedAt: IsoInstant | null;
}
export const setupApi = {
  preset: (signal?: AbortSignal) => getJson<RunPreset>(`${API_BASE}/runs/preset`, signal),
  runRecommendations: (datasetId: string, signal?: AbortSignal) =>
    getJson<Recommendation[]>(withQuery(`${API_BASE}/runs/recommendations`, { datasetId }), signal),
  captureRecommendations: (wallet: string, scope: string, mint: string, signal?: AbortSignal) =>
    getJson<Recommendation[]>(
      withQuery(`${API_BASE}/captures/recommendations`, { wallet, scope, mint }),
      signal,
    ),
  activity: (address: string, before?: string, refresh = false, signal?: AbortSignal) =>
    getJson<WalletActivity>(
      withQuery(`${API_BASE}/wallets/${encodeURIComponent(address)}/activity`, {
        limit: 20,
        before,
        refresh: refresh ? 'true' : undefined,
      }),
      signal,
    ),
  usage: (period: UsagePeriod, captureId?: string | null, signal?: AbortSignal) =>
    getJson<UsageReport>(withQuery(`${API_BASE}/usage`, { period, captureId }), signal),
};
