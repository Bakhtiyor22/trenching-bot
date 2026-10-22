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
import type { ChartRequest, ChartResponse, RunStartRequest } from './types';

/** Runs started from this tab; the new-run watcher skips them, since starting one already notifies. */
export const locallyStartedRuns = new Set<string>();

/** Dashboard polling interval (plan decision 7). */
export const DASHBOARD_POLL_MS = 5_000;
const HEALTH_POLL_MS = 15_000;
/** How often the shell looks for runs started elsewhere (another tab, or someone on a shared link). */
export const LATEST_RUNS_POLL_MS = 10_000;
const LATEST_RUNS_SIZE = 5;
const SOLANA_STATUS_POLL_MS = 15_000;
/** Matches the backend's 30-second ticker cache. */
const SOL_PRICE_POLL_MS = 30_000;
/** A USDT view can be unavailable for a while (rate limited, unreachable), so it is not kept forever. */
const USDT_VIEW_STALE_MS = 60_000;

export const queryKeys = {
  datasets: (page: number, size: number) => ['datasets', page, size] as const,
  datasetsAll: ['datasets'] as const,
  dataset: (id: string) => ['dataset', id] as const,
  datasetObservations: (id: string, pair: string | null) =>
    ['dataset', id, 'observations', pair] as const,
  datasetChart: (id: string, request: ChartRequest) => ['dataset', id, 'chart', request] as const,
  configDefaults: ['config', 'defaults'] as const,
  runsAll: ['runs'] as const,
  runs: (page: number, size: number, datasetId: string | null) =>
    ['runs', page, size, datasetId] as const,
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

export function useRuns(page: number, size: number, datasetId: string | null) {
  return useQuery({
    queryKey: queryKeys.runs(page, size, datasetId),
    queryFn: ({ signal }) => api.runs(page, size, datasetId, signal),
    placeholderData: keepPreviousData,
  });
}

/** The newest runs, polled so the shell can announce runs started elsewhere. */
export function useLatestRuns() {
  return useQuery({
    queryKey: queryKeys.runs(0, LATEST_RUNS_SIZE, null),
    queryFn: ({ signal }) => api.runs(0, LATEST_RUNS_SIZE, null, signal),
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
