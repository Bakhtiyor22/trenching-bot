/** Route paths, relative to the router basename `/console`. */
export const ROUTER_BASENAME = '/console';

export const paths = {
  dashboard: '/',
  datasets: '/datasets',
  dataset: (id: string) => `/datasets/${encodeURIComponent(id)}`,
  newRun: '/runs/new',
  newRunFor: (datasetId: string) => `/runs/new?datasetId=${encodeURIComponent(datasetId)}`,
  runs: '/runs',
  runsFor: (datasetId: string) => `/runs?datasetId=${encodeURIComponent(datasetId)}`,
  run: (id: string) => `/runs/${encodeURIComponent(id)}`,
  solana: '/solana',
} as const;
