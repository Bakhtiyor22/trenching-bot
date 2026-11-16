import { Group, Text } from '@mantine/core';
import { Spotlight, type SpotlightActionGroupData } from '@mantine/spotlight';
import { useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { useAutomationStatus, useAutomationSweepRuns } from '../api/automation';
import { useDatasets, useRuns } from '../api/queries';
import type { Dataset, RunSummary } from '../api/types';
import { dayInfo, tradingDayOf } from '../features/automation/automationModel';
import { isSolanaAddress } from '../lib/base58';
import { formatSignedDecimal, pnlColor } from '../lib/decimal';
import { formatCount, formatLocalTime, mintLabel, shortId } from '../lib/format';
import { paths } from '../paths';
import {
  IconAutomation,
  IconCapture,
  IconCompare,
  IconDashboard,
  IconDatabase,
  IconGauge,
  IconPlay,
  IconRuns,
  IconSatellite,
  IconSearch,
} from './Icons';

/** How many of the newest runs and datasets the palette can jump to. */
const PALETTE_RUNS = 50;
const PALETTE_DATASETS = 100;
/** Results shown at once; typing narrows them. */
const PALETTE_LIMIT = 14;
/** Leading characters of a run id shown as its name. */
const RUN_LABEL_CHARS = 8;

/** Runs of a pasted mint listed under it; the full list is one click away on the Runs page. */
const MINT_RUNS = 5;

/** Keys that open the palette from anywhere (Mantine hotkey syntax). */
const PALETTE_SHORTCUTS = ['mod + K', '/'];

function Hint({ children }: { children: ReactNode }) {
  return (
    <Text size="xs" c="dimmed" ff="monospace">
      {children}
    </Text>
  );
}

function runAction(run: RunSummary, open: (to: string) => void, group = 'run') {
  return {
    id: `${group}-${run.id}`,
    label: `Run ${run.id.slice(0, RUN_LABEL_CHARS)}`,
    description: `${formatLocalTime(run.createdAt)} · ${run.closedCount} closed · dataset ${run.datasetName || shortId(run.datasetId)}`,
    // The full ids make a pasted hash or a prefix of one match; the mints make a pasted token match.
    keywords: [run.id, run.datasetId, run.configurationHash, ...run.mints],
    rightSection: (
      <Text
        size="sm"
        ff="monospace"
        fw={600}
        c={pnlColor(run.realizedPnl)}
        style={{ whiteSpace: 'nowrap' }}
      >
        {formatSignedDecimal(run.realizedPnl)} {mintLabel(run.quoteMint)}
      </Text>
    ),
    onClick: () => open(paths.run(run.id)),
  };
}

function datasetAction(dataset: Dataset, open: (to: string) => void) {
  return {
    id: `dataset-${dataset.id}`,
    label: dataset.name,
    description: `${dataset.transactionCount} transactions · ${dataset.observationCount} observations`,
    keywords: [dataset.id, dataset.trackedWallet],
    leftSection: <IconDatabase size={16} />,
    onClick: () => open(paths.dataset(dataset.id)),
  };
}

/**
 * The Ctrl+K palette: jump to any page, run or dataset, or start an action. Runs and datasets match
 * on their full ids too, so pasting a hash finds it.
 */
/**
 * A pasted token mint: its runs (`/api/runs?mint=`) and its automation buy (`/api/automation/sweep-runs?mint=`), which
 * opens on its day page with the buy selected. Every action keeps the mint as a keyword so the palette's filter keeps it.
 */
function useMintActions(query: string, open: (to: string) => void) {
  const mint = query.trim();
  const valid = isSolanaAddress(mint);
  const runs = useRuns({ page: 0, size: MINT_RUNS, mint }, { enabled: valid });
  const buys = useAutomationSweepRuns({ mint, page: 0, size: 1 }, valid);
  const status = useAutomationStatus({ enabled: valid });
  if (!valid) return { group: null, runIds: new Set<string>(), searching: false };
  const actions = [];
  const buy = buys.data?.items[0];
  if (buy?.blockTime && status.data) {
    const day = tradingDayOf(buy.blockTime, status.data.zone, status.data.tradingDayStart);
    actions.push({
      id: `mint-buy-${buy.itemId}`,
      label: `Automation buy of ${mintLabel(mint)}`,
      description: `Trading day ${dayInfo(day).label} · opens the day with this buy selected`,
      keywords: [mint],
      leftSection: <IconAutomation size={16} />,
      onClick: () => open(paths.automationDay(day, buy.itemId)),
    });
  }
  const total = runs.data?.total ?? 0;
  if (total > 0) {
    actions.push({
      id: `mint-runs-${mint}`,
      label: `Runs of ${mintLabel(mint)}`,
      description: `${formatCount(total)} ${total === 1 ? 'run' : 'runs'} · opens the Runs page filtered to this token`,
      keywords: [mint],
      leftSection: <IconRuns size={16} />,
      onClick: () => open(paths.runsOfMint(mint)),
    });
    for (const run of runs.data!.items) actions.push(runAction(run, open, 'mint-run'));
  }
  return {
    group: actions.length > 0 ? { group: `Token ${mintLabel(mint)}`, actions } : null,
    runIds: new Set((runs.data?.items ?? []).map((run) => run.id)),
    searching: runs.isFetching || buys.isFetching || status.isFetching,
  };
}

export function CommandPalette() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const runs = useRuns({ page: 0, size: PALETTE_RUNS });
  const datasets = useDatasets(0, PALETTE_DATASETS);
  const open = (to: string) => void navigate(to);
  const mint = useMintActions(query, open);

  const actions: SpotlightActionGroupData[] = [
    {
      group: 'Go to',
      actions: [
        {
          id: 'go-dashboard',
          label: 'Dashboard',
          leftSection: <IconDashboard size={16} />,
          onClick: () => open(paths.dashboard),
        },
        {
          id: 'go-runs',
          label: 'Runs',
          leftSection: <IconRuns size={16} />,
          onClick: () => open(paths.runs),
        },
        {
          id: 'go-new-run',
          label: 'New run',
          leftSection: <IconPlay size={16} />,
          onClick: () => open(paths.newRun),
        },
        {
          id: 'go-compare',
          label: 'Compare runs',
          leftSection: <IconCompare size={16} />,
          onClick: () => open(paths.compare),
        },
        {
          id: 'go-datasets',
          label: 'Datasets',
          leftSection: <IconDatabase size={16} />,
          onClick: () => open(paths.datasets),
        },
        {
          id: 'go-captures',
          label: 'Captures',
          leftSection: <IconCapture size={16} />,
          onClick: () => open(paths.captures),
        },
        {
          id: 'go-automation',
          label: 'Automation: sweep scoreboard',
          keywords: ['always on', 'buys', 'trading days', 'variations'],
          leftSection: <IconAutomation size={16} />,
          onClick: () => open(paths.automation),
        },
        {
          id: 'go-usage',
          label: 'API usage',
          keywords: ['requests', 'rate limit', 'provider'],
          leftSection: <IconGauge size={16} />,
          onClick: () => open(paths.usage),
        },
        {
          id: 'go-solana',
          label: 'Solana connectivity',
          leftSection: <IconSatellite size={16} />,
          onClick: () => open(paths.solana),
        },
      ],
    },
    {
      group: 'Actions',
      actions: [
        {
          id: 'new-run',
          label: 'Start a new run',
          leftSection: <IconPlay size={16} />,
          rightSection: <Hint>N</Hint>,
          onClick: () => open(paths.newRun),
        },
        {
          id: 'new-capture',
          label: 'Create or attach a capture',
          leftSection: <IconCapture size={16} />,
          onClick: () => open(paths.captures),
        },
      ],
    },
    ...(mint.group ? [mint.group] : []),
    {
      group: 'Runs',
      actions: (runs.data?.items ?? [])
        .filter((run) => !mint.runIds.has(run.id))
        .map((run) => runAction(run, open)),
    },
    { group: 'Datasets', actions: (datasets.data?.items ?? []).map((d) => datasetAction(d, open)) },
  ];

  return (
    <Spotlight
      actions={actions}
      query={query}
      onQueryChange={setQuery}
      shortcut={PALETTE_SHORTCUTS}
      limit={PALETTE_LIMIT}
      highlightQuery
      scrollable
      maxHeight={440}
      nothingFound={
        <Group justify="center" p="md">
          <Text size="sm" c="dimmed">
            {isSolanaAddress(query.trim())
              ? mint.searching
                ? 'Looking for runs and an automation buy of this token…'
                : 'No run and no automation buy holds this token.'
              : 'Nothing matches. Runs and datasets match on any part of their id; paste a token mint to find its runs and buy.'}
          </Text>
        </Group>
      }
      searchProps={{
        leftSection: <IconSearch size={18} />,
        placeholder: 'Search runs, datasets, token mints, pages…',
        'aria-label': 'Command palette search',
      }}
    />
  );
}
