import { API_BASE, deleteRequest, getJson, postForm, postJson, withQuery } from './client';
import type {
  ActuatorHealth,
  AddressCheck,
  ConfigDefaults,
  Dashboard,
  Dataset,
  DatasetRegistration,
  Observation,
  Page,
  Pair,
  RunDetail,
  RunEvent,
  RunOrder,
  RunPosition,
  RunStart,
  RunStartRequest,
  RunSummary,
  RunUsdtView,
  SolanaCheck,
  SolanaStatus,
  SolUsdtPrice,
} from './types';

/** The bundled sample dataset id in `POST /api/datasets/samples/{sample}`. */
export const SAMPLE_DATASET = 'dip-sample-v3';

const enc = encodeURIComponent;

export function pairKey(pair: Pair): string {
  return `${pair.base}/${pair.quote}`;
}

export const api = {
  datasets: (page: number, size: number, signal?: AbortSignal) =>
    getJson<Page<Dataset>>(withQuery(`${API_BASE}/datasets`, { page, size }), signal),
  dataset: (id: string, signal?: AbortSignal) =>
    getJson<Dataset>(`${API_BASE}/datasets/${enc(id)}`, signal),
  deleteDataset: (id: string) => deleteRequest(`${API_BASE}/datasets/${enc(id)}`),
  datasetObservations: (id: string, pair: string | null, signal?: AbortSignal) =>
    getJson<Observation[]>(
      withQuery(`${API_BASE}/datasets/${enc(id)}/observations`, { pair }),
      signal,
    ),
  uploadDataset: (file: File, name?: string) => {
    const form = new FormData();
    form.append('file', file, file.name);
    if (name && name.trim()) form.append('name', name.trim());
    return postForm<DatasetRegistration>(`${API_BASE}/datasets`, form);
  },
  importSample: (sample: string = SAMPLE_DATASET) =>
    postJson<DatasetRegistration>(`${API_BASE}/datasets/samples/${enc(sample)}`),

  configDefaults: (signal?: AbortSignal) =>
    getJson<ConfigDefaults>(`${API_BASE}/config/defaults`, signal),

  startRun: (body: RunStartRequest) => postJson<RunStart>(`${API_BASE}/runs`, body),
  runs: (page: number, size: number, datasetId: string | null, signal?: AbortSignal) =>
    getJson<Page<RunSummary>>(withQuery(`${API_BASE}/runs`, { page, size, datasetId }), signal),
  run: (id: string, signal?: AbortSignal) => getJson<RunDetail>(`${API_BASE}/runs/${enc(id)}`, signal),
  runEvents: (id: string, signal?: AbortSignal) =>
    getJson<RunEvent[]>(`${API_BASE}/runs/${enc(id)}/events`, signal),
  runObservations: (id: string, signal?: AbortSignal) =>
    getJson<Observation[]>(`${API_BASE}/runs/${enc(id)}/observations`, signal),
  runOrders: (id: string, signal?: AbortSignal) =>
    getJson<RunOrder[]>(`${API_BASE}/runs/${enc(id)}/orders`, signal),
  runPositions: (id: string, signal?: AbortSignal) =>
    getJson<RunPosition[]>(`${API_BASE}/runs/${enc(id)}/positions`, signal),
  runReportUrl: (id: string) => `${API_BASE}/runs/${enc(id)}/report`,
  runUsdt: (id: string, signal?: AbortSignal) =>
    getJson<RunUsdtView>(`${API_BASE}/runs/${enc(id)}/usdt`, signal),
  solUsdtPrice: (signal?: AbortSignal) => getJson<SolUsdtPrice>(`${API_BASE}/market/sol-usdt`, signal),

  dashboard: (signal?: AbortSignal) => getJson<Dashboard>(`${API_BASE}/dashboard`, signal),
  actuatorHealth: (signal?: AbortSignal) => getJson<ActuatorHealth>('/actuator/health', signal),

  solanaStatus: (signal?: AbortSignal) => getJson<SolanaStatus>(`${API_BASE}/solana/status`, signal),
  solanaCheck: () => postJson<SolanaCheck>(`${API_BASE}/solana/check`),
  addressCheck: (address: string) =>
    postJson<AddressCheck>(`${API_BASE}/solana/address-check`, { address }),
};
