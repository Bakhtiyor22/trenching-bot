import {
  Accordion,
  Anchor,
  Box,
  Button,
  Group,
  Pagination,
  ScrollArea,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { Link, useSearchParams } from 'react-router';
import { CAPTURE_STATUSES, type CaptureSummary } from '../../api/captures';
import { RelativeTime, ShortId } from '../../components/DisplayValues';
import { ConfirmDelete } from '../../components/ConfirmDelete';
import { Meter } from '../../components/Meter';
import { PageHeader, Section } from '../../components/Panels';
import { QueryView } from '../../components/QueryView';
import { EmptyState } from '../../components/StateViews';
import {
  actionLabels,
  captureScopeLabels,
  codeLabel,
  stageLabels,
  statusLabels,
} from '../../lib/codes';
import { formatCount, humanizeCode } from '../../lib/format';
import { paths } from '../../paths';
import { CaptureStatusChip, LiveCue } from './CaptureShared';
import {
  capturePollInterval,
  isCaptureOperationOpen,
  useCaptureConfig,
  useCaptures,
  useDeleteCapture,
} from './captureQueries';
import { nextActionHint } from './useNextAction';

/** Captures per list page; the backend's default page size. */
const LIST_PAGE_SIZE = 20;
/** The status filter value that shows every capture (no `?status`). */
const ALL_STATUSES = 'ALL';
const sortOptions = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'name,asc', label: 'Name' },
  { value: 'attention', label: 'Needs attention first' },
];
const stepStates = {
  DONE: { label: 'done', color: 'var(--console-gain)' },
  ACTIVE: { label: 'running', color: 'var(--console-info)' },
  STOPPED: { label: 'stopped', color: 'var(--console-rejected)' },
  FAILED: { label: 'needs attention', color: 'var(--console-loss)' },
  TODO: { label: 'waiting', color: 'var(--console-line-strong)' },
};

function ListProgress({ summary }: { summary: CaptureSummary }) {
  const done = summary.steps.every((step) => step.state === 'DONE');
  return (
    <Stack gap="xs">
      <Group
        gap={4}
        wrap="nowrap"
        role="img"
        aria-label={summary.steps
          .map((step) => `${stageLabels[step.stage]} ${stepStates[step.state].label}`)
          .join(', ')}
      >
        {summary.steps.map((step) => (
          <Box
            key={step.stage}
            h={6}
            style={{ flex: 1, borderRadius: 3, background: stepStates[step.state].color }}
          />
        ))}
      </Group>
      <Text size="xs">
        {done
          ? 'All steps complete'
          : `Step ${summary.currentStep.index} of 4 · ${stageLabels[summary.currentStep.stage]}`}
      </Text>
      {summary.progress && (
        <>
          <Meter
            label="Capture progress"
            value={summary.progress.processed}
            max={summary.progress.selected}
          />
          <Text size="xs">
            {formatCount(summary.progress.processed)} of {formatCount(summary.progress.selected)}{' '}
            transactions · {summary.progress.percent}%
          </Text>
        </>
      )}
    </Stack>
  );
}

function statusReason(summary: CaptureSummary) {
  if (summary.reasonCode) return codeLabel(summary.reasonCode);
  if (!summary.cases.total) return null;
  return `${summary.cases.ready} of ${summary.cases.total} tokens ready${summary.cases.blocked ? `; ${summary.cases.blocked} still blocked` : ''}`;
}

/** The four steps explained, as a quiet disclosure above the list. */
function StepsExplained() {
  return (
    <Accordion
      chevronPosition="left"
      styles={{
        item: { borderBottom: 0 },
        control: { paddingInline: 0, background: 'transparent' },
        label: { fontSize: 'var(--mantine-font-size-sm)', color: 'var(--console-text-2)' },
        content: { paddingInline: 0 },
      }}
    >
      <Accordion.Item value="steps">
        <Accordion.Control>What do the four steps mean?</Accordion.Control>
        <Accordion.Panel>
          <SimpleGrid cols={{ base: 1, xs: 4 }}>
            <Text size="sm" c="dimmed">
              1. Wallet: find the buys this capture will follow.
            </Text>
            <Text size="sm" c="dimmed">
              2. Markets: record the trades after each buy.
            </Text>
            <Text size="sm" c="dimmed">
              3. Rates: save SOL/USDT prices for the recorded minutes.
            </Text>
            <Text size="sm" c="dimmed">
              4. Dataset: check and package the saved evidence for a replay. No provider requests.
            </Text>
          </SimpleGrid>
        </Accordion.Panel>
      </Accordion.Item>
    </Accordion>
  );
}

export function CapturesPage() {
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Math.floor(Number(params.get('page'))) || 0);
  const sort = params.get('sort') ?? 'createdAt,desc';
  const status = params.get('status');
  const captures = useCaptures(page, sort, status);
  const config = useCaptureConfig();
  const remove = useDeleteCapture();
  const labels = new Map(
    [...(config.data?.recentWallets ?? []), ...(config.data?.suggestedWallets ?? [])].map(
      (wallet) => [wallet.address, wallet.label],
    ),
  );
  const counts = captures.data?.statusCounts;
  const totalCount = counts
    ? Object.values(counts).reduce((total, count) => total + count, 0)
    : (captures.data?.total ?? 0);
  const pages = Math.max(1, Math.ceil((captures.data?.total ?? 0) / LIST_PAGE_SIZE));
  const live = !!captures.data?.items.some((item) => capturePollInterval(item));
  const filters = [
    { value: ALL_STATUSES, label: `All · ${formatCount(totalCount)}` },
    ...CAPTURE_STATUSES.filter((value) => (counts?.[value] ?? 0) > 0 || status === value).map(
      (value) => ({
        value,
        label: `${statusLabels[value]} · ${formatCount(counts?.[value] ?? 0)}`,
      }),
    ),
  ];
  function change(key: string, value: string | null) {
    setParams((current) => {
      const next = new URLSearchParams(current);
      if (value) next.set(key, value);
      else next.delete(key);
      if (key !== 'page') next.delete('page');
      return next;
    });
  }
  return (
    <>
      <PageHeader
        title="Captures"
        description="Record a wallet’s trades, check the token’s market, then prepare a dataset for a paper run."
        actions={
          <Button component={Link} to={paths.newCapture}>
            New capture
          </Button>
        }
      />
      <Stack gap="lg">
        <StepsExplained />
        <Group justify="space-between" align="flex-end" gap="md">
          <ScrollArea type="auto" style={{ flex: '1 1 320px', minWidth: 0 }}>
            <SegmentedControl
              aria-label="Capture status filters"
              value={status ?? ALL_STATUSES}
              onChange={(value) => change('status', value === ALL_STATUSES ? null : value)}
              data={filters}
            />
          </ScrollArea>
          <Select
            label="Sort captures"
            value={sort}
            allowDeselect={false}
            onChange={(value) => change('sort', value)}
            data={sortOptions}
            w={{ base: '100%', sm: 240 }}
          />
        </Group>
        <Section title="Capture sessions" actions={<LiveCue active={live} />}>
          <QueryView
            query={captures}
            loadingLabel="Loading captures…"
            errorTitle="Could not load captures"
          >
            {(data) =>
              !data.items.length ? (
                <EmptyState
                  title={status ? 'No captures with this status' : 'No captures yet'}
                  description="Start with a wallet and one token. The four steps save everything you need to replay the strategy offline."
                  action={
                    <Group justify="center">
                      <Button component={Link} to={paths.newCapture}>
                        {config.data?.suggestedWallets?.[0]
                          ? `New capture with ${config.data.suggestedWallets[0].label}`
                          : 'New capture'}
                      </Button>
                      <Button
                        component={Link}
                        variant="default"
                        to={`${paths.newCapture}?mode=attach`}
                      >
                        Attach a saved capture folder
                      </Button>
                    </Group>
                  }
                />
              ) : (
                <Box
                  style={{
                    opacity: captures.isPlaceholderData ? 0.6 : 1,
                    transition: 'opacity 120ms',
                  }}
                >
                  <ScrollArea type="auto">
                    <Table miw={1050} verticalSpacing="xs">
                      <Table.Thead>
                        <Table.Tr>
                          {['Capture', 'Status', 'Progress', 'Next step', 'Created', ''].map(
                            (title, index) => (
                              <Table.Th key={index}>{title}</Table.Th>
                            ),
                          )}
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {data.items.map((item) => (
                          <Table.Tr key={item.id}>
                            <Table.Td>
                              <Stack gap={5}>
                                <Anchor component={Link} to={paths.capture(item.id)} fw={600}>
                                  {item.displayName ?? item.name}
                                </Anchor>
                                <Group gap="xs">
                                  {item.wallet
                                    ? labels.get(item.wallet.wallet) || (
                                        <ShortId value={item.wallet.wallet} />
                                      )
                                    : 'Attached evidence'}
                                  <Text span size="xs" c="dimmed">
                                    {' '}
                                    ·{' '}
                                  </Text>
                                  {item.wallet?.mint ? (
                                    <ShortId value={item.wallet.mint} />
                                  ) : (
                                    `${formatCount(item.summary?.cases.total ?? 0)} tokens`
                                  )}
                                </Group>
                                {item.wallet && (
                                  <Text size="xs" c="dimmed">
                                    {captureScopeLabels[item.wallet.scope] ??
                                      humanizeCode(item.wallet.scope)}
                                  </Text>
                                )}
                                {item.displayName && item.displayName !== item.name && (
                                  <Text size="xs" c="dimmed">
                                    Two captures share this name; numbered by creation time
                                  </Text>
                                )}
                              </Stack>
                            </Table.Td>
                            <Table.Td miw={170}>
                              <Stack gap="xs" align="flex-start">
                                <CaptureStatusChip status={item.summary?.status ?? 'NOT_STARTED'} />
                                {item.summary && statusReason(item.summary) && (
                                  <Text size="xs">{statusReason(item.summary)}</Text>
                                )}
                              </Stack>
                            </Table.Td>
                            <Table.Td miw={230}>
                              {item.summary ? (
                                <ListProgress summary={item.summary} />
                              ) : (
                                <Text size="xs">Ready for the wallet step</Text>
                              )}
                            </Table.Td>
                            <Table.Td>
                              <Stack gap="xs" align="flex-start">
                                <Button
                                  component={Link}
                                  to={paths.capture(item.id)}
                                  variant="default"
                                  size="xs"
                                >
                                  {item.summary
                                    ? actionLabels[item.summary.nextAction.kind]
                                    : 'Open capture'}
                                </Button>
                                {item.summary && (
                                  <Text size="xs" c="dimmed">
                                    {nextActionHint(item.summary)}
                                  </Text>
                                )}
                              </Stack>
                            </Table.Td>
                            <Table.Td style={{ whiteSpace: 'nowrap' }}>
                              <RelativeTime iso={item.createdAt} />
                            </Table.Td>
                            <Table.Td>
                              <ConfirmDelete
                                compact
                                label="Delete"
                                title="Delete this capture?"
                                disabled={isCaptureOperationOpen(item.operation?.state)}
                                onConfirm={() => remove.mutateAsync(item.id)}
                              >
                                Removes “{item.name}” and its managed evidence. Attached evidence
                                and imported datasets stay.
                              </ConfirmDelete>
                            </Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  </ScrollArea>
                  <Group justify="space-between" mt="sm">
                    <Text size="sm" c="dimmed">
                      Showing {formatCount(page * LIST_PAGE_SIZE + 1)}–
                      {formatCount(Math.min((page + 1) * LIST_PAGE_SIZE, data.total))} of{' '}
                      {formatCount(data.total)} ·{' '}
                      {sortOptions.find((option) => option.value === sort)?.label}
                    </Text>
                    {pages > 1 && (
                      <Pagination
                        value={page + 1}
                        total={pages}
                        onChange={(value) => change('page', String(value - 1))}
                        size="sm"
                      />
                    )}
                  </Group>
                </Box>
              )
            }
          </QueryView>
        </Section>
      </Stack>
    </>
  );
}
