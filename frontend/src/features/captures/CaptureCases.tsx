import { Alert, Button, Card, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import { useCallback, useEffect, type ReactNode } from 'react';
import { capturesApi, type CaptureCase } from '../../api/captures';
import { KeyValueTable, Section } from '../../components/Panels';
import { Timestamp } from '../../components/DisplayValues';
import { ErrorState } from '../../components/StateViews';
import { paths } from '../../paths';
import { codeLabel } from '../../lib/codes';
import { CodeText } from '../../components/PlainLanguage';
import { CaptureStatus, CommandError } from './CaptureShared';
import {
  captureKeys,
  CAPTURE_POLL_MS,
  isCaptureOperationActive,
  isCaptureOperationOpen,
  useCaptureCases,
  useCaptureCommand,
} from './captureQueries';

const count = (value: number) => value.toLocaleString('en-US');
const readinessLabel = (state: CaptureCase['readiness']) =>
  ({
    READY_TO_VALIDATE: 'Ready for validation',
    READY: 'Validated and ready',
    BLOCKED: 'Blocked',
    RUNNING: 'Case capture running',
    WAITING_RETRY: 'Retry scheduled',
    PAUSED: 'Paused',
    CANCELLED: 'Cancelled',
    FAILED: 'Failed',
  })[state];

function CaseCard({ sessionId, item }: { sessionId: string; item: CaptureCase }) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const refresh = useCallback(async () => {
    await client.invalidateQueries({ queryKey: captureKeys.cases(sessionId) });
    await client.invalidateQueries({ queryKey: captureKeys.session(sessionId) });
  }, [client, sessionId]);
  const rates = useCaptureCommand(
    `${sessionId}:${item.caseId}:rates`,
    (body: { requestId: string }) => capturesApi.caseRates(sessionId, item.caseId, body),
    refresh,
  );
  const exportCase = useCaptureCommand(
    `${sessionId}:${item.caseId}:export`,
    (body: { requestId: string }) => capturesApi.caseExport(sessionId, item.caseId, body),
    refresh,
  );
  const operation = useQuery({
    queryKey: [...captureKeys.cases(sessionId), item.caseId, 'operation', item.operationId],
    queryFn: ({ signal }) => capturesApi.operation(sessionId, item.operationId ?? '', signal),
    enabled: !!item.operationId,
    refetchInterval: (query) =>
      isCaptureOperationActive(query.state.data?.state ?? item.operationState)
        ? CAPTURE_POLL_MS
        : false,
    refetchIntervalInBackground: false,
  });
  const registration = useMutation({
    mutationFn: () => capturesApi.caseRegister(sessionId, item.caseId),
    onSuccess: async (result) => {
      await refresh();
      await client.invalidateQueries({ queryKey: ['datasets'] });
      await navigate(paths.dataset(result.dataset.id));
    },
  });
  const operationState = operation.data?.state ?? item.operationState;
  const operationActive =
    isCaptureOperationOpen(item.readiness) || isCaptureOperationOpen(operationState);
  // Refresh once when the operation settles. Keyed on its state, not the response object: the
  // refresh refetches this operation too, and a new object would trigger the refresh again.
  const settledState = operation.data?.state;
  useEffect(() => {
    if (!item.operationId || !settledState || isCaptureOperationOpen(settledState)) return;
    void refresh();
  }, [item.operationId, settledState, refresh]);
  const progress = item.progress;
  return (
    <Card withBorder padding="md" component="article" aria-label={`Capture case ${item.caseId}`}>
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start">
          <Stack gap={3} style={{ minWidth: 0 }}>
            <Text fw={600} ff="monospace" style={{ overflowWrap: 'anywhere' }}>
              {item.mint}
            </Text>
            <Text size="xs" c="dimmed" style={{ overflowWrap: 'anywhere' }}>
              Case {item.caseId}
            </Text>
          </Stack>
          <CaptureStatus state={item.readiness} label={readinessLabel(item.readiness)} />
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <KeyValueTable
            rows={[
              [
                'Wallet',
                <Text size="sm" ff="monospace" key="wallet" style={{ overflowWrap: 'anywhere' }}>
                  {item.wallet}
                </Text>,
              ],
              [
                'Anchor',
                <Text size="sm" ff="monospace" key="anchor" style={{ overflowWrap: 'anywhere' }}>
                  {item.anchorSignature} · slot {count(item.anchorSlot)} · instruction{' '}
                  {item.instructionIndex}
                </Text>,
              ],
              ['Market window', `${count(item.fromSlot)}–${count(item.toSlot)}`],
              ['Market stage / coverage', `${item.marketStage} · ${item.coverage}`],
            ]}
          />
          <KeyValueTable
            rows={[
              [
                'Selected / captured / gaps',
                `${count(item.selected)} / ${count(item.captured)} / ${count(item.gaps)}`,
              ],
              ['Capture complete', item.captureComplete ? 'Yes' : 'No'],
              ['Validated / ready', item.validated && item.ready ? 'Yes' : 'No'],
              ...(progress
                ? [
                    [
                      'Progress',
                      `${progress.phase} · ${count(progress.processed ?? 0)} / ${count(progress.selected ?? 0)}`,
                    ] as [string, ReactNode],
                  ]
                : []),
              ...(progress?.at
                ? [
                    ['Last progress', <Timestamp iso={progress.at} key="progress-time" />] as [
                      string,
                      ReactNode,
                    ],
                  ]
                : []),
            ]}
          />
        </SimpleGrid>
        {item.blocker && (
          <Alert
            color={item.readiness === 'READY_TO_VALIDATE' ? 'blue' : 'amber'}
            title={item.readiness === 'READY_TO_VALIDATE' ? 'Validation needed' : 'Case is blocked'}
          >
            {codeLabel(item.blocker)}
            <CodeText code={item.blocker} />
          </Alert>
        )}
        {operation.isError && (
          <ErrorState
            error={operation.error}
            title="Could not refresh case operation"
            onRetry={() => void operation.refetch()}
          />
        )}
        {operation.data?.message && (
          <Alert color={operation.data.state === 'FAILED' ? 'red' : 'amber'}>
            {operation.data.message}
          </Alert>
        )}
        <Group gap="xs" wrap="wrap">
          {item.canCaptureRates && (
            <Button
              size="sm"
              disabled={operationActive || rates.isPending || !!rates.pendingCommand}
              onClick={() => rates.submit({})}
            >
              Validate and prepare rates
            </Button>
          )}
          {item.canExport && (
            <Button
              size="sm"
              disabled={operationActive || exportCase.isPending || !!exportCase.pendingCommand}
              onClick={() => exportCase.submit({})}
            >
              Export validated case
            </Button>
          )}
          {item.exportReady && (
            <Button
              size="sm"
              variant="default"
              component="a"
              href={capturesApi.caseDownload(sessionId, item.caseId)}
            >
              Download case dataset JSON
            </Button>
          )}
          {item.canRegisterDataset && (
            <Button
              size="sm"
              disabled={registration.isPending}
              loading={registration.isPending}
              onClick={() => registration.mutate()}
            >
              Import case dataset
            </Button>
          )}
          {item.datasetId && (
            <>
              <Button
                size="sm"
                variant="default"
                component={Link}
                to={paths.dataset(item.datasetId)}
              >
                Open dataset
              </Button>
              <Button
                size="sm"
                variant="default"
                component={Link}
                to={paths.newRunFor(item.datasetId)}
              >
                New run
              </Button>
            </>
          )}
        </Group>
        <CommandError
          error={rates.error}
          uncertain={!!rates.pendingCommand && !rates.isPending}
          retry={rates.retry}
        />
        <CommandError
          error={exportCase.error}
          uncertain={!!exportCase.pendingCommand && !exportCase.isPending}
          retry={exportCase.retry}
        />
        {registration.isError && (
          <ErrorState error={registration.error} title="Case dataset import failed" />
        )}
        {operationActive && operation.data?.recovery?.nextRetryAt && (
          <Text size="xs" c="dimmed">
            Next retry at <Timestamp iso={operation.data.recovery.nextRetryAt} />. Case operations
            do not pause the main capture batch.
          </Text>
        )}
      </Stack>
    </Card>
  );
}

export function CaptureCases({
  sessionId,
  mainOperationState,
}: {
  sessionId: string;
  mainOperationState?: string;
}) {
  const query = useCaptureCases(sessionId, mainOperationState);
  return (
    <Section
      title="Independent market cases"
      hint="Each case advances when its own capture, rates and validation are ready. Captured files alone do not make a case ready for replay."
    >
      {query.isPending ? (
        <Text size="sm" c="dimmed">
          Loading market cases…
        </Text>
      ) : query.isError ? (
        <ErrorState
          error={query.error}
          title="Could not load market cases"
          onRetry={() => void query.refetch()}
        />
      ) : query.data.items.length === 0 ? (
        <Text size="sm" c="dimmed">
          No market cases are available yet. Cases appear after wallet anchors and market windows
          are inspected.
        </Text>
      ) : (
        <Stack gap="sm">
          {query.data.items.map((item) => (
            <CaseCard key={item.caseId} sessionId={sessionId} item={item} />
          ))}
        </Stack>
      )}
    </Section>
  );
}
