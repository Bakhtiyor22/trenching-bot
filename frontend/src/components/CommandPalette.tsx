import { Group, Text } from '@mantine/core';
import { Spotlight, type SpotlightActionGroupData } from '@mantine/spotlight';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import { useDatasets, useRuns } from '../api/queries';
import type { Dataset, RunSummary } from '../api/types';
import { formatDecimal, pnlColor } from '../lib/decimal';
import { formatLocalTime, mintLabel, shortId } from '../lib/format';
import { paths } from '../paths';
import {
  IconCapture,
  IconCompare,
  IconDashboard,
  IconDatabase,
  IconList,
  IconPlay,
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

/** Keys that open the palette from anywhere (Mantine hotkey syntax). */
const PALETTE_SHORTCUTS = ['mod + K', '/'];

function Hint({ children }: { children: ReactNode }) {
  return (
    <Text size="xs" c="dimmed" ff="monospace">
      {children}
    </Text>
  );
}

function runAction(run: RunSummary, open: (to: string) => void) {
  return {
    id: `run-${run.id}`,
    label: `Run ${run.id.slice(0, RUN_LABEL_CHARS)}`,
    description: `${formatLocalTime(run.createdAt)} · ${run.closedCount} closed · dataset ${shortId(run.datasetId)}`,
    // The full ids make a pasted hash or a prefix of one match.
    keywords: [run.id, run.datasetId, run.configurationHash],
    rightSection: (
      <Text size="sm" ff="monospace" fw={600} c={pnlColor(run.realizedPnl)}>
        {formatDecimal(run.realizedPnl)} {mintLabel(run.quoteMint)}
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
export function CommandPalette() {
  const navigate = useNavigate();
  const runs = useRuns(0, PALETTE_RUNS, null);
  const datasets = useDatasets(0, PALETTE_DATASETS);
  const open = (to: string) => void navigate(to);

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
          leftSection: <IconList size={16} />,
          onClick: () => open(paths.runs),
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
    { group: 'Runs', actions: (runs.data?.items ?? []).map((run) => runAction(run, open)) },
    { group: 'Datasets', actions: (datasets.data?.items ?? []).map((d) => datasetAction(d, open)) },
  ];

  return (
    <Spotlight
      actions={actions}
      shortcut={PALETTE_SHORTCUTS}
      limit={PALETTE_LIMIT}
      highlightQuery
      scrollable
      maxHeight={440}
      nothingFound={
        <Group justify="center" p="md">
          <Text size="sm" c="dimmed">
            Nothing matches. Runs and datasets match on any part of their id.
          </Text>
        </Group>
      }
      searchProps={{
        leftSection: <IconSearch size={18} />,
        placeholder: 'Search runs, datasets, pages…',
        'aria-label': 'Command palette search',
      }}
    />
  );
}
