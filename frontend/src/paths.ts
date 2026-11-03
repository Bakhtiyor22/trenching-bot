/** Route paths, relative to the router basename `/console`. */
export const ROUTER_BASENAME = '/console';

/** The query parameter holding the compared run ids, comma separated. */
export const COMPARE_PARAM = 'runs';
/** The comparison view shows at most this many runs side by side. */
export const MAX_COMPARED_RUNS = 3;

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
  captures: '/captures',
  capture: (id: string) => `/captures/${encodeURIComponent(id)}`,
  newCapture: '/captures/new',
  usage: '/usage',
  automation: '/automation',
  automationDays: '/automation/days',
  automationDay: (day: string, itemId?: string | null) =>
    `/automation/days/${encodeURIComponent(day)}${itemId ? `?item=${encodeURIComponent(itemId)}` : ''}`,
  automationNight: (id: string) => `/automation/nights/${encodeURIComponent(id)}`,
  automationBackfills: '/automation/backfills',
  automationBackfill: (id: string) => `/automation/backfills/${encodeURIComponent(id)}`,
  automationItem: (id: string) => `/automation/items/${encodeURIComponent(id)}`,
  automationSweepRuns: '/automation/sweep-runs',
  runChart: (id: string) => `/runs/${encodeURIComponent(id)}/chart`,
  compare: '/compare',
  compareRuns: (ids: string[]) =>
    `/compare?${COMPARE_PARAM}=${ids.map(encodeURIComponent).join(',')}`,
} as const;
