import {
  keepPreviousData,
  QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { ApiError } from './client';
import { api } from './endpoints';
import type { RunStartRequest } from './types';

/** Dashboard polling interval (plan decision 7). */
export const DASHBOARD_POLL_MS = 5_000;
const HEALTH_POLL_MS = 15_000;
const SOLANA_STATUS_POLL_MS = 15_000;

export const queryKeys = {
  datasets: (page: number, size: number) => ['datasets', page, size] as const,
  datasetsAll: ['datasets'] as const,
  dataset: (id: string) => ['dataset', id] as const,
  datasetObservations: (id: string, pair: string | null) =>
    ['dataset', id, 'observations', pair] as const,
  configDefaults: ['config', 'defaults'] as const,
  runsAll: ['runs'] as const,
  runs: (page: number, size: number, datasetId: string | null) =>
    ['runs', page, size, datasetId] as const,
  run: (id: string) => ['run', id] as const,
  runRecords: (id: string, kind: 'events' | 'observations' | 'orders' | 'positions') =>
    ['run', id, kind] as const,
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

export function useUploadDataset() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ file, name }: { file: File; name?: string }) => api.uploadDataset(file, name),
    onSuccess: () => client.invalidateQueries({ queryKey: queryKeys.datasetsAll }),
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
    onSuccess: async () => {
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
