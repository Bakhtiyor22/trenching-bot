import {
  keepPreviousData,
  QueryClient,
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ApiError } from './client';
import { api } from './endpoints';
import type { ChartRequest, ChartResponse, RunQuery, RunStartRequest } from './types';

/** Runs started from this tab; the new-run watcher skips them, since starting one already notifies. */
export const locallyStartedRuns = new Set<string>();

/** Dashboard polling interval (plan decision 7). */
export const DASHBOARD_POLL_MS = 5_000;
const HEALTH_POLL_MS = 15_000;
/** How often the shell looks for runs started elsewhere (another tab, or someone on a shared link). */
export const LATEST_RUNS_POLL_MS = 10_000;
const LATEST_RUNS_SIZE = 5;
/** How often the Runs page checks for runs newer than its snapshot. */
const LIVE_RUN_TOTAL_POLL_MS = 30_000;
/** The server's minimum for a free-text `q` (runs and datasets). */
export const MIN_SEARCH_CHARS = 2;
/** The server's maximum for a free-text `q`. */
export const MAX_SEARCH_CHARS = 100;
const SOLANA_STATUS_POLL_MS = 15_000;
/** Matches the backend's 30-second ticker cache. */
const SOL_PRICE_POLL_MS = 30_000;
/** A USDT view can be unavailable for a while (rate limited, unreachable), so it is not kept forever. */
const USDT_VIEW_STALE_MS = 60_000;

export const queryKeys = {
  datasets: (page: number, size: number) => ['datasets', page, size] as const,
  datasetSearch: (q: string, size: number) => ['datasets', 'search', q, size] as const,
  datasetsAll: ['datasets'] as const,
  dataset: (id: string) => ['dataset', id] as const,
  datasetObservations: (id: string, pair: string | null) =>
    ['dataset', id, 'observations', pair] as const,
  datasetChart: (id: string, request: ChartRequest) => ['dataset', id, 'chart', request] as const,
  configDefaults: ['config', 'defaults'] as const,
  runsAll: ['runs'] as const,
  runs: (query: RunQuery) => ['runs', query] as const,
  run: (id: string) => ['run', id] as const,
  runRecords: (id: string, kind: 'events' | 'observations' | 'orders' | 'positions') =>
    ['run', id, kind] as const,
  runUsdt: (id: string) => ['run', id, 'usdt'] as const,
  runChart: (id: string, request: ChartRequest) => ['run', id, 'chart', request] as const,
  solUsdtPrice: ['market', 'sol-usdt'] as const,
  dashboard: ['dashboard'] as const,
  actuatorHealth: ['actuator', 'health'] as const,
  solanaStatus: ['solana', 'status'] as const,
};

/** Retries transient failures only; a 4xx means the request itself is wrong. */
function shouldRetry(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.isClientError) return false;
  return failureCount < 2;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetry, refetchOnWindowFocus: false, staleTime: 10_000 },
      mutations: { retry: false },
    },
  });
}

export function useDatasets(page: number, size: number) {
  return useQuery({
    queryKey: queryKeys.datasets(page, size),
    queryFn: ({ signal }) => api.datasets(page, size, signal),
    placeholderData: keepPreviousData,
  });
}

/** Datasets matching `q` on the server; without a usable `q` (under 2 characters) the newest ones. */
export function useDatasetSearch(q: string, size: number) {
  const needle = q.trim();
  const searched = needle.length >= MIN_SEARCH_CHARS;
  return useQuery({
    queryKey: searched ? queryKeys.datasetSearch(needle, size) : queryKeys.datasets(0, size),
    queryFn: ({ signal }) =>
      searched ? api.searchDatasets(needle, size, signal) : api.datasets(0, size, signal),
    placeholderData: keepPreviousData,
  });
}

export function useDataset(id: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.dataset(id),
    queryFn: ({ signal }) => api.dataset(id, signal),
    enabled,
  });
}

export function useDatasetObservations(id: string, pair: string | null, enabled = true) {
  return useQuery({
    queryKey: queryKeys.datasetObservations(id, pair),
    queryFn: ({ signal }) => api.datasetObservations(id, pair, signal),
    enabled,
  });
}

/** The server repeats candles but sends clusters only once; retain the first page's chart. */
async function completeChart(
  fetchPage: (request: ChartRequest, signal: AbortSignal) => Promise<ChartResponse>,
  request: ChartRequest,
  signal: AbortSignal,
): Promise<ChartResponse> {
  signal.throwIfAborted();
  const first = await fetchPage(request, signal);
  const items = [...first.markers.items];
  const cursors = new Set<string>();
  let cursor = first.markers.nextCursor;
  while (cursor !== null) {
    signal.throwIfAborted();
    if (cursors.has(cursor))
      throw new ApiError(0, 'BAD_RESPONSE', 'The chart returned a repeated marker cursor.');
    cursors.add(cursor);
    const page = await fetchPage({ ...request, cursor }, signal);
    items.push(...page.markers.items);
    cursor = page.markers.nextCursor;
  }
  signal.throwIfAborted();
  return { ...first, markers: { ...first.markers, items, nextCursor: null } };
}

export function useDatasetChart(id: string, request: ChartRequest, enabled = true) {
  return useQuery({
    queryKey: queryKeys.datasetChart(id, request),
    queryFn: ({ signal }) =>
      completeChart((page, pageSignal) => api.datasetChart(id, page, pageSignal), request, signal),
    enabled,
    placeholderData: keepPreviousData,
    staleTime: Infinity,
  });
}

export function useRunChart(id: string, request: ChartRequest, enabled = true) {
  return useQuery({
    queryKey: queryKeys.runChart(id, request),
    queryFn: ({ signal }) =>
      completeChart((page, pageSignal) => api.runChart(id, page, pageSignal), request, signal),
    enabled,
    placeholderData: keepPreviousData,
    staleTime: Infinity,
  });
}

export function useUploadDataset() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ file, name }: { file: File; name?: string }) => api.uploadDataset(file, name),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.datasetsAll }),
  });
}

/** Removes a dataset from the catalog; its runs stay, so only dataset and dashboard views refresh. */
export function useDeleteDataset() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteDataset(id),
    onSuccess: async (_, id) => {
      client.removeQueries({ queryKey: queryKeys.dataset(id) });
      await Promise.all([
        client.invalidateQueries({ queryKey: queryKeys.datasetsAll }),
        client.invalidateQueries({ queryKey: queryKeys.dashboard }),
      ]);
    },
  });
}

export function useImportSample() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.importSample(),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.datasetsAll }),
  });
}

export function useConfigDefaults() {
  return useQuery({
    queryKey: queryKeys.configDefaults,
    queryFn: ({ signal }) => api.configDefaults(signal),
    staleTime: Infinity,
  });
}

export function useStartRun() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: RunStartRequest) => api.startRun(body),
    onSuccess: async ({ run }) => {
      locallyStartedRuns.add(run.id);
      await client.invalidateQueries({ queryKey: queryKeys.runsAll });
      await client.invalidateQueries({ queryKey: queryKeys.dashboard });
    },
  });
}

export function useRuns(query: RunQuery, { enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: queryKeys.runs(query),
    queryFn: ({ signal }) => api.runs(query, signal),
    placeholderData: keepPreviousData,
    enabled,
  });
}

/**
 * How many runs match `query` now, without a snapshot: the Runs page compares it with its snapshot's total to offer the
 * runs created since. Polled only while the tab is visible.
 */
export function useLiveRunTotal(query: RunQuery, enabled: boolean) {
  const live: RunQuery = { ...query, page: 0, size: 1, asOf: null };
  return useQuery({
    queryKey: queryKeys.runs(live),
    queryFn: ({ signal }) => api.runs(live, signal),
    select: (page) => page.total,
    refetchInterval: LIVE_RUN_TOTAL_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
    enabled,
  });
}

/** The newest runs, polled so the shell can announce runs started elsewhere. */
export function useLatestRuns() {
  const query: RunQuery = { page: 0, size: LATEST_RUNS_SIZE };
  return useQuery({
    queryKey: queryKeys.runs(query),
    queryFn: ({ signal }) => api.runs(query, signal),
    refetchInterval: LATEST_RUNS_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
  });
}

/** Several runs at once, for the comparison view. */
export function useRunsById(ids: string[]) {
  return useQueries({
    queries: ids.map((id) => ({
      queryKey: queryKeys.run(id),
      queryFn: ({ signal }: { signal: AbortSignal }) => api.run(id, signal),
      staleTime: Infinity,
    })),
  });
}

/** Runs are immutable experiments, so their detail never goes stale. */
export function useRun(id: string) {
  return useQuery({
    queryKey: queryKeys.run(id),
    queryFn: ({ signal }) => api.run(id, signal),
    staleTime: Infinity,
  });
}

export function useRunEvents(id: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.runRecords(id, 'events'),
    queryFn: ({ signal }) => api.runEvents(id, signal),
    enabled,
    staleTime: Infinity,
  });
}

export function useRunObservations(id: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.runRecords(id, 'observations'),
    queryFn: ({ signal }) => api.runObservations(id, signal),
    enabled,
    staleTime: Infinity,
  });
}

export function useRunUsdt(id: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.runUsdt(id),
    queryFn: ({ signal }) => api.runUsdt(id, signal),
    enabled,
    staleTime: USDT_VIEW_STALE_MS,
  });
}

export function useSolUsdtPrice(enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.solUsdtPrice,
    queryFn: ({ signal }) => api.solUsdtPrice(signal),
    enabled,
    refetchInterval: SOL_PRICE_POLL_MS,
    refetchIntervalInBackground: false,
  });
}

export function useRunOrders(id: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.runRecords(id, 'orders'),
    queryFn: ({ signal }) => api.runOrders(id, signal),
    enabled,
    staleTime: Infinity,
  });
}

export function useRunPositions(id: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.runRecords(id, 'positions'),
    queryFn: ({ signal }) => api.runPositions(id, signal),
    enabled,
    staleTime: Infinity,
  });
}

/**
 * Polls every 5 s. TanStack Query pauses interval refetches while the tab is hidden
 * (`refetchIntervalInBackground: false`), so a background tab costs nothing.
 */
export function useDashboard() {
  return useQuery({
    queryKey: queryKeys.dashboard,
    queryFn: ({ signal }) => api.dashboard(signal),
    refetchInterval: DASHBOARD_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
  });
}

export function useActuatorHealth() {
  return useQuery({
    queryKey: queryKeys.actuatorHealth,
    queryFn: ({ signal }) => api.actuatorHealth(signal),
    refetchInterval: HEALTH_POLL_MS,
    refetchIntervalInBackground: false,
    retry: false,
    staleTime: 0,
  });
}

/** Reads configuration and the cached last check only; it never calls the RPC. */
export function useSolanaStatus() {
  return useQuery({
    queryKey: queryKeys.solanaStatus,
    queryFn: ({ signal }) => api.solanaStatus(signal),
    refetchInterval: SOLANA_STATUS_POLL_MS,
    refetchIntervalInBackground: false,
    staleTime: 0,
  });
}

export function useSolanaCheck() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.solanaCheck(),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.solanaStatus }),
  });
}

export function useAddressCheck() {
  return useMutation({ mutationFn: (address: string) => api.addressCheck(address) });
}
