import type { Page } from './types';
import type { DatasetRegistration } from './types';
import { API_BASE, deleteRequest, getJson, postJson, withQuery } from './client';

export type CaptureStep = 'WALLET' | 'MARKET' | 'RATES' | 'INSPECT' | 'EXPORT' | 'CALIBRATE' | 'QUALIFY';
/** The wallet-capture scope that takes one mint and verifies the wallet's actual first Pump.fun curve buy of it. */
export const WALLET_TOKEN_SCOPE = 'wallet-token';
/** `mint` is required (base58) only for {@link WALLET_TOKEN_SCOPE} and omitted for the other scopes. */
export interface WalletSettings { wallet: string; scope: string; limit: number; scanPages: number; mint?: string | null }
export interface MarketSettings { scanPages: number; maxTransactions: number }
/** One explicit operation's allowance; the deadline is whole minutes. */
export interface CaptureBudget { requestBudget: number; byteBudget: number; operationTimeoutMinutes: number }
export interface CaptureStage { state: string; reason: string | null; canStart: boolean; canResume: boolean; requiresNewAttempt: boolean }
export interface CaptureAttempt { id: string; stage: 'MARKET' | 'RATES'; createdAt: string; attached: boolean; market: MarketSettings | null; walletAttemptId: string; marketAttemptId: string | null }
export type VerificationListing = 'MINT' | 'PUMP_ACCUMULATOR' | 'PUMPSWAP_ACCUMULATOR';
/** A step 2 cap reached before its bound, with how far each listing got. */
export interface VerificationBound { limit: 'MINT_PAGES' | 'PUMP_ACCUMULATOR_PAGES' | 'PUMPSWAP_ACCUMULATOR_PAGES' | 'FETCHES'; pages: Record<VerificationListing, number>; entries: Record<VerificationListing, number>; fetches: number; oldestSlot: Record<VerificationListing, number | null> }
/** The earliest decoded buy through the wallet's token account; it proves only what happened through that account. */
export interface TokenAccountFirstBuy { state: 'FOUND' | 'NO_BUY' | 'NOT_ESTABLISHED'; signature?: string | null; slot?: number | null; reason?: string | null; blocking: string[] }
/** The wallet's actual first Pump.fun curve buy of the mint; `claim` is the backend's coverage claim, shown verbatim. */
export interface ActualFirstBuy { state: 'PROVEN' | 'UNPROVEN' | 'OUTSIDE_TOKEN_ACCOUNT' | 'NOT_VERIFIED'; signature?: string | null; slot?: number | null; creationSignature?: string | null; creationSlot?: number | null; creationTime?: string | null; reason?: string | null; blocking: string[]; claim?: string | null; bound?: VerificationBound | null }
export interface TargetToken { mint: string; tokenProgram: string; tokenAccount: string; pumpAccumulator: string; pumpSwapAccumulator: string; tokenAccountFirstBuy: TokenAccountFirstBuy; actualFirstBuy: ActualFirstBuy }
export type VerdictClassification = 'QUALIFYING_DIP' | 'NEGATIVE_CASE' | 'INCOMPLETE_CAPTURE' | 'FIRST_BUY_UNPROVEN' | 'FIRST_BUY_OUTSIDE_TOKEN_ACCOUNT' | 'NO_TARGET_BUY' | 'REPLAY_FAILED';
/** A wallet-token session's verdict; before export it comes from inspection alone, with replay NOT_RUN. Decimals are exact strings. */
export interface Verdict {
  classification: VerdictClassification;
  capture: { state: 'COMPLETE' | 'INCOMPLETE' | 'UNUSABLE'; stage?: string | null; reason?: string | null };
  dip: { state: 'QUALIFIED' | 'NOT_QUALIFIED' | 'NOT_EVALUATED'; reason?: string | null; triggerSlot?: number | null; triggerPrice?: string | null; marketCapUsd?: string | null; rate?: string | null; observationId?: string | null };
  entry: { state: 'FILLED' | 'NOT_FILLED'; reason?: string | null; slot?: number | null; executionPrice?: string | null; observationId?: string | null } | null;
  exit: { state: 'CLOSED' | 'OPEN'; reason: string; slot?: number | null; realizedPnl?: string | null } | null;
  replay: { state: 'RAN' | 'FAILED' | 'NOT_RUN'; category?: string | null };
  provenance: { datasetChecksum?: string | null; runId?: string | null; configurationHash?: string | null; engineVersion?: string | null; referencePrice?: string | null; firstBuySignature?: string | null; firstBuySlot?: number | null };
}
export interface CaptureInspection { targetToken?: TargetToken | null; wallet: CaptureStage; market: CaptureStage; rates: CaptureStage; canCalibrate: boolean; canExport: boolean; walletSummary: Record<string, string>; marketSummary: Record<string, string>; ratesSummary: Record<string, string>; marketWindows: Array<{ mint: string; fromSlot: number; toSlot: number; coverage: string; scanned: number; selected: number; detailGaps: number | null; observations: number | null }> }
export interface CaptureOperation { id: string; requestId: string; step: CaptureStep; attemptId: string | null; state: string; startedAt: string; updatedAt: string; endedAt: string | null; budget: CaptureBudget; scanPages: number | null; progress: { phase: string; at: string; operationRequests: number | null; operationBytes: number | null; cumulativeRequests: number | null; cumulativeBytes: number | null; processed: number | null; selected: number | null } | null; code: string | null; message: string | null }
export type BuyInstruction = 'BUY' | 'BUY_EXACT_QUOTE_IN_V3';
export interface CalibrationBuy { signature: string; slot: number; mint: string; solAmount: string; instruction: BuyInstruction; math: { exact: boolean; tokenAmount: string; computedTokenAmount: string | null; feeLamports: string; computedFeeLamports: string; creatorFeeLamports: string; computedCreatorFeeLamports: string }; latency: { status: string; observationId?: string; observationSlot?: number; predictedTokenAmount?: string; errorBps?: string; reason?: string }; costs: { transactionFeeLamports: string; otherLamports: string } }
export interface CalibrationReport { wallet: string; decoder: string; extractor: string; summary: { buys: number; mathExact: number; mathMismatches: number; latencyChecked: number; noPriorState: number; unpredictable: number; medianErrorBps: string | null; maxErrorBps: string | null; medianTransactionFeeLamports: string | null; medianOtherLamports: string | null; instructions?: Record<string, number> }; mathMismatches: string[]; buys: CalibrationBuy[] }
export interface CaptureSession { id: string; name: string; createdAt: string; wallet: WalletSettings | null; walletAttemptId: string; marketAttempt: CaptureAttempt | null; ratesAttempt: CaptureAttempt | null; inspection: CaptureInspection | null; operation: CaptureOperation | null; datasetReady: boolean; calibrationReady: boolean; datasetId: string | null; calibration: CalibrationReport | null; qualification?: Verdict | null }
export type CaptureBoundKey = `${'wallet' | 'market'}${'RequestsMax' | 'BytesMax' | 'PagesMax'}` | 'walletLimitMax' | 'marketTransactionsMax' | 'operationTimeoutMinutesMax';
export interface CaptureConfig { rpcConfigured: boolean; ratesConfigured: boolean; walletDefaults: WalletSettings; walletTokenDefaults: WalletSettings; walletTokenBudgetDefaults: CaptureBudget; marketDefaults: MarketSettings; budgetDefaults: CaptureBudget; bounds: Record<CaptureBoundKey, number>; windowSlots: number }
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
  remove: (id: string) => deleteRequest(sessionUrl(id)),
};

