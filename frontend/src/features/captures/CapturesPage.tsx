import {
  Accordion,
  Anchor,
  Box,
  Button,
  Group,
  Pagination,
  Progress,
  ScrollArea,
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
import { PageHeader, Section } from '../../components/Panels';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { actionLabels, codeLabel, stageLabels, statusLabels } from '../../lib/codes';
import { paths } from '../../paths';
import { CaptureStatusChip } from './CaptureShared';
import {
  isCaptureOperationOpen,
  useCaptureConfig,
  useCaptures,
  useDeleteCapture,
} from './captureQueries';
import { nextActionHint } from './useNextAction';

const sortOptions = [
  { value: 'createdAt,desc', label: 'Newest first' },
  { value: 'createdAt,asc', label: 'Oldest first' },
  { value: 'name,asc', label: 'Name' },
  { value: 'attention', label: 'Needs attention first' },
];
const scopeLabels: Record<string, string> = {
  'wallet-token': 'Wallet + token',
  'pumpfun-user-buys': 'Pump.fun buys',
  'wallet-references': 'Wallet references',
};
const stepStates = {
  DONE: { label: 'done', color: 'teal' },
  ACTIVE: { label: 'running', color: 'blue' },
  STOPPED: { label: 'stopped', color: 'yellow' },
  FAILED: { label: 'needs attention', color: 'red' },
  TODO: { label: 'waiting', color: 'dark' },
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
            style={{ flex: 1, borderRadius: 3 }}
            bg={`var(--mantine-color-${stepStates[step.state].color}-6)`}
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
          <Progress value={Number(summary.progress.percent)} aria-label="Capture progress" />
          <Text size="xs">
            {summary.progress.processed.toLocaleString('en-US')} of{' '}
            {summary.progress.selected.toLocaleString('en-US')} transactions ·{' '}
            {summary.progress.percent}%
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

export function CapturesPage() {
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page')) || 0);
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
  const pages = Math.max(1, Math.ceil((captures.data?.total ?? 0) / 20));
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
    <Stack gap="lg">
      <PageHeader
        title="Captures"
        description="Record a wallet’s trades, check the token’s market, then prepare a dataset for a paper run."
        actions={
          <Button component={Link} to={paths.newCapture}>
            New capture
          </Button>
        }
      />
      <Accordion variant="separated">
        <Accordion.Item value="steps">
          <Accordion.Control>What do the four steps mean?</Accordion.Control>
          <Accordion.Panel>
            <SimpleGrid cols={{ base: 1, xs: 4 }}>
              <Text size="sm">1. Wallet: find the buys this capture will follow.</Text>
              <Text size="sm">2. Markets: record the trades after each buy.</Text>
              <Text size="sm">3. Rates: save SOL/USDT prices for the recorded minutes.</Text>
              <Text size="sm">
                4. Dataset: check and package the saved evidence for a replay. No provider requests.
              </Text>
            </SimpleGrid>
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>
      <Group justify="space-between" align="flex-start">
        <Group gap="xs" aria-label="Capture status filters" style={{ flex: 1 }}>
          <Button
            variant={!status ? 'filled' : 'default'}
            size="xs"
            aria-pressed={!status}
            onClick={() => change('status', null)}
          >
            All · {totalCount}
          </Button>
          {CAPTURE_STATUSES.filter((value) => (counts?.[value] ?? 0) > 0 || status === value).map(
            (value) => (
              <Button
                key={value}
                size="xs"
                variant={status === value ? 'filled' : 'default'}
                aria-pressed={status === value}
                onClick={() => change('status', value)}
              >
                {statusLabels[value]} · {counts?.[value] ?? 0}
              </Button>
            ),
          )}
        </Group>
        <Select
          label="Sort captures"
          value={sort}
          allowDeselect={false}
          onChange={(value) => change('sort', value)}
          data={sortOptions}
        />
      </Group>
      <Section title="Capture sessions">
        {captures.isPending ? (
          <LoadingState />
        ) : captures.isError ? (
          <ErrorState error={captures.error} onRetry={() => void captures.refetch()} />
        ) : !captures.data.items.length ? (
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
                <Button component={Link} variant="default" to={`${paths.newCapture}?mode=attach`}>
                  Attach a saved capture folder
                </Button>
              </Group>
            }
          />
        ) : (
          <>
            <ScrollArea>
              <Table miw={1050} verticalSpacing="md">
                <Table.Thead>
                  <Table.Tr>
                    {['Capture', 'Status', 'Progress', 'Next step', 'Created ↓', ''].map(
                      (title, index) => (
                        <Table.Th key={index}>{title}</Table.Th>
                      ),
                    )}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {captures.data.items.map((item) => (
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
                              `${item.summary?.cases.total ?? 0} tokens`
                            )}
                          </Group>
                          {item.wallet && (
                            <Text size="xs" c="dimmed">
                              {scopeLabels[item.wallet.scope] ?? item.wallet.scope}
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
                        <Stack gap="xs">
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
                            variant={
                              item.summary &&
                              ['WAIT', 'VIEW_RESULTS', 'NONE'].includes(
                                item.summary.nextAction.kind,
                              )
                                ? 'default'
                                : 'filled'
                            }
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
                      <Table.Td>
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
                          Removes “{item.name}” and its managed evidence. Attached evidence and
                          imported datasets stay.
                        </ConfirmDelete>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollArea>
            <Group justify="space-between" mt="sm">
              <Text size="sm" c="dimmed">
                Showing {page * 20 + 1}–{Math.min((page + 1) * 20, captures.data.total)} of{' '}
                {captures.data.total} · {sortOptions.find((option) => option.value === sort)?.label}
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
          </>
        )}
      </Section>
    </Stack>
  );
}
