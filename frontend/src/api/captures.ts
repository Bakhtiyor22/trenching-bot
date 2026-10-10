import type { Page } from './types';
import type { DatasetRegistration } from './types';
import { API_BASE, getJson, postJson, withQuery } from './client';

export type CaptureStep = 'WALLET' | 'MARKET' | 'RATES' | 'INSPECT' | 'EXPORT' | 'CALIBRATE';
export interface WalletSettings { wallet: string; scope: string; limit: number; scanPages: number }
export interface MarketSettings { scanPages: number; maxTransactions: number }
/** One explicit operation's allowance; the deadline is whole minutes. */
export interface CaptureBudget { requestBudget: number; byteBudget: number; operationTimeoutMinutes: number }
export interface CaptureStage { state: string; reason: string | null; canStart: boolean; canResume: boolean; requiresNewAttempt: boolean }
export interface CaptureAttempt { id: string; stage: 'MARKET' | 'RATES'; createdAt: string; attached: boolean; market: MarketSettings | null; walletAttemptId: string; marketAttemptId: string | null }
export interface CaptureInspection { wallet: CaptureStage; market: CaptureStage; rates: CaptureStage; canCalibrate: boolean; canExport: boolean; walletSummary: Record<string, string>; marketSummary: Record<string, string>; ratesSummary: Record<string, string>; marketWindows: Array<{ mint: string; fromSlot: number; toSlot: number; coverage: string; scanned: number; selected: number; detailGaps: number | null; observations: number | null }> }
export interface CaptureOperation { id: string; requestId: string; step: CaptureStep; attemptId: string | null; state: string; startedAt: string; updatedAt: string; endedAt: string | null; budget: CaptureBudget; scanPages: number | null; progress: { phase: string; at: string; operationRequests: number | null; operationBytes: number | null; cumulativeRequests: number | null; cumulativeBytes: number | null; processed: number | null; selected: number | null } | null; code: string | null; message: string | null }
export type BuyInstruction = 'BUY' | 'BUY_EXACT_QUOTE_IN_V3';
export interface CalibrationBuy { signature: string; slot: number; mint: string; solAmount: string; instruction: BuyInstruction; math: { exact: boolean; tokenAmount: string; computedTokenAmount: string | null; feeLamports: string; computedFeeLamports: string; creatorFeeLamports: string; computedCreatorFeeLamports: string }; latency: { status: string; observationId?: string; observationSlot?: number; predictedTokenAmount?: string; errorBps?: string; reason?: string }; costs: { transactionFeeLamports: string; otherLamports: string } }
export interface CalibrationReport { wallet: string; decoder: string; extractor: string; summary: { buys: number; mathExact: number; mathMismatches: number; latencyChecked: number; noPriorState: number; unpredictable: number; medianErrorBps: string | null; maxErrorBps: string | null; medianTransactionFeeLamports: string | null; medianOtherLamports: string | null; instructions?: Record<string, number> }; mathMismatches: string[]; buys: CalibrationBuy[] }
export interface CaptureSession { id: string; name: string; createdAt: string; wallet: WalletSettings | null; walletAttemptId: string; marketAttempt: CaptureAttempt | null; ratesAttempt: CaptureAttempt | null; inspection: CaptureInspection | null; operation: CaptureOperation | null; datasetReady: boolean; calibrationReady: boolean; datasetId: string | null; calibration: CalibrationReport | null }
export type CaptureBoundKey = `${'wallet' | 'market'}${'RequestsMax' | 'BytesMax' | 'PagesMax'}` | 'walletLimitMax' | 'marketTransactionsMax' | 'operationTimeoutMinutesMax';
export interface CaptureConfig { rpcConfigured: boolean; ratesConfigured: boolean; walletDefaults: WalletSettings; marketDefaults: MarketSettings; budgetDefaults: CaptureBudget; bounds: Record<CaptureBoundKey, number>; windowSlots: number }
export interface CaptureCreate { requestId: string; name: string; wallet?: WalletSettings; attach?: { wallet: string; market?: string; rates?: string } }
export interface CaptureAttemptRequest { requestId: string; stage: 'MARKET' | 'RATES'; market?: MarketSettings }
export interface CaptureOperationRequest { requestId: string; step: CaptureStep; attemptId?: string; budget?: CaptureBudget; scanPages?: number }
const base = `${API_BASE}/captures`;
const sessionUrl = (id: string) => `${base}/${encodeURIComponent(id)}`;
export const capturesApi = {
  config: (signal?: AbortSignal) => getJson<CaptureConfig>(`${base}/config`, signal),
  list: (page: number, signal?: AbortSignal) => getJson<Page<CaptureSession>>(withQuery(base, { page, size: 20 }), signal),
  session: (id: string, signal?: AbortSignal) => getJson<CaptureSession>(sessionUrl(id), signal),
  create: (body: CaptureCreate) => postJson<CaptureSession>(base, body),
  attempts: (id: string, page: number, signal?: AbortSignal) => getJson<Page<CaptureAttempt>>(withQuery(`${sessionUrl(id)}/attempts`, { page, size: 20 }), signal),
  newAttempt: (id: string, body: CaptureAttemptRequest) => postJson<CaptureSession>(`${sessionUrl(id)}/attempts`, body),
  operate: (id: string, body: CaptureOperationRequest) => postJson<CaptureOperation>(`${sessionUrl(id)}/operations`, body),
  operation: (id: string, operationId: string, signal?: AbortSignal) => getJson<CaptureOperation>(`${sessionUrl(id)}/operations/${encodeURIComponent(operationId)}`, signal),
  import: (id: string) => postJson<DatasetRegistration>(`${sessionUrl(id)}/dataset`),
  download: (id: string, kind: 'dataset' | 'calibration') => `${sessionUrl(id)}/downloads/${kind}`,
};

