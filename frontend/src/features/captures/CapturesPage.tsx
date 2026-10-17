import {
  Accordion,
  Anchor,
  Badge,
  Button,
  Group,
  Progress,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { Link, useSearchParams } from 'react-router';
import { CAPTURE_STATUSES } from '../../api/captures';
import { ShortId, Timestamp } from '../../components/DisplayValues';
import { ConfirmDelete } from '../../components/ConfirmDelete';
import { PageHeader, PagingFooter, Section } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { EmptyState, ErrorState, LoadingState } from '../../components/StateViews';
import { actionLabels, codeLabel, statusLabels } from '../../lib/codes';
import { paths } from '../../paths';
import { CaptureStepper } from './CaptureOverview';
import { isCaptureOperationOpen, useCaptures, useDeleteCapture } from './captureQueries';

export function CapturesPage() {
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page')) || 0);
  const sort = params.get('sort') ?? 'createdAt,desc';
  const status = params.get('status');
  const captures = useCaptures(page, sort, status);
  const remove = useDeleteCapture();
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
            <Text size="sm">
              1. Wallet: find the reference buy. 2. Markets: record the trades that follow. 3.
              Rates: record SOL/USDT prices and validate the evidence. 4. Dataset: export and
              register the evidence for a replay.
            </Text>
          </Accordion.Panel>
        </Accordion.Item>
      </Accordion>
      <Group justify="space-between" align="flex-start">
        <Group gap="xs" aria-label="Capture status filters">
          <Button
            variant={!status ? 'filled' : 'default'}
            size="xs"
            onClick={() => change('status', null)}
          >
            All
          </Button>
          {CAPTURE_STATUSES.map((value) => (
            <Button
              key={value}
              size="xs"
              variant={status === value ? 'filled' : 'default'}
              onClick={() => change('status', value)}
            >
              {statusLabels[value]} ({captures.data?.statusCounts?.[value] ?? 0})
            </Button>
          ))}
        </Group>
        <Select
          label="Sort captures"
          value={sort}
          allowDeselect={false}
          onChange={(value) => change('sort', value)}
          data={[
            { value: 'createdAt,desc', label: 'Newest first' },
            { value: 'createdAt,asc', label: 'Oldest first' },
            { value: 'name,asc', label: 'Name' },
            { value: 'attention', label: 'Needs attention first' },
          ]}
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
            description="Create a capture to start recording a token, or attach existing evidence."
          />
        ) : (
          <>
            <ScrollArea>
              <Table miw={850} verticalSpacing="md">
                <Table.Thead>
                  <Table.Tr>
                    {['Capture', 'Progress', 'Created', 'Next step', ''].map((title, index) => (
                      <Table.Th key={index}>{title}</Table.Th>
                    ))}
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
                          {item.wallet ? (
                            <ShortId value={item.wallet.wallet} />
                          ) : (
                            <Text size="xs">Attached evidence</Text>
                          )}
                          {item.wallet?.mint && <ShortId value={item.wallet.mint} />}
                        </Stack>
                      </Table.Td>
                      <Table.Td miw={280}>
                        {item.summary ? (
                          <Stack gap="xs">
                            <Badge
                              variant="light"
                              color={item.summary.status === 'NEEDS_ATTENTION' ? 'yellow' : 'teal'}
                            >
                              {statusLabels[item.summary.status]}
                            </Badge>
                            <Text size="xs">
                              Step {item.summary.currentStep.index} of 4 ·{' '}
                              {item.summary.currentStep.stage.toLowerCase()}
                            </Text>
                            <CaptureStepper session={item} />
                            {item.summary.progress && (
                              <>
                                <Progress
                                  value={Number(item.summary.progress.percent)}
                                  aria-label="Capture progress"
                                />
                                <Text size="xs">
                                  {item.summary.progress.percent}% ·{' '}
                                  {item.summary.progress.processed} /{' '}
                                  {item.summary.progress.selected} transactions
                                </Text>
                              </>
                            )}
                            {item.summary.reasonCode && (
                              <Text size="xs">{codeLabel(item.summary.reasonCode)}</Text>
                            )}
                            <CodeText code={item.summary.reasonCode} />
                          </Stack>
                        ) : (
                          <Text size="sm">{item.operation?.state ?? 'Not started'}</Text>
                        )}
                      </Table.Td>
                      <Table.Td>
                        <Timestamp iso={item.createdAt} />
                      </Table.Td>
                      <Table.Td>
                        <Button
                          component={Link}
                          to={paths.capture(item.id)}
                          variant="light"
                          disabled={captures.data.items.some((session) =>
                            isCaptureOperationOpen(session.operation?.state),
                          )}
                          title={
                            captures.data.items.some((session) =>
                              isCaptureOperationOpen(session.operation?.state),
                            )
                              ? 'A capture operation is running. Open the capture name to inspect it.'
                              : undefined
                          }
                        >
                          {item.summary
                            ? actionLabels[item.summary.nextAction.kind]
                            : 'Open capture'}
                        </Button>
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
            <PagingFooter
              page={page + 1}
              pages={Math.max(1, Math.ceil(captures.data.total / 20))}
              total={captures.data.total}
              onChange={(value) => change('page', String(value - 1))}
              noun="captures"
            />
          </>
        )}
      </Section>
    </Stack>
  );
}
