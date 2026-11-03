import { Badge, Button, Card, Group, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import type { CaptureConfig, CaptureSession, CaptureSummary } from '../../api/captures';
import { setupApi } from '../../api/setup';
import { CodeText } from '../../components/PlainLanguage';
import type { StatusTone } from '../../components/StatusChip';
import { stageLabels, statusTones } from '../../lib/codes';
import { formatCount, mintLabel } from '../../lib/format';
import { CommandError } from './CaptureShared';
import { nowCopy } from './captureNow';
import { nextActionHint, useNextAction } from './useNextAction';

function stepState(summary: CaptureSummary, step: CaptureSummary['steps'][number]) {
  if (step.state === 'DONE') return 'Done';
  if (step.state === 'FAILED') return 'Needs attention';
  if (step.state === 'ACTIVE')
    return summary.status === 'WAITING_RETRY' ? 'Waiting to retry' : 'In progress';
  if (step.state === 'STOPPED') {
    if (summary.status === 'STOPPED_LIMIT') return 'Stopped · request limit';
    if (summary.status === 'STOPPED_DEADLINE') return 'Stopped · time limit';
    if (summary.status === 'PAUSED') return 'Paused';
    if (summary.status === 'CANCELLED') return 'Cancelled';
    return 'Stopped';
  }
  const previous = { WALLET: null, MARKETS: 'wallet', RATES: 'markets', DATASET: 'rates' }[
    step.stage
  ];
  return previous ? `Waiting for ${previous}` : 'Ready to start';
}

function marketDetail(session: CaptureSession) {
  const summaryProgress =
    session.summary?.currentStep.stage === 'MARKETS' ? session.summary.progress : null;
  const operationProgress =
    session.operation?.step === 'MARKET' &&
    session.operation.attemptId === session.marketAttempt?.id
      ? session.operation.progress
      : null;
  const progress = summaryProgress ?? operationProgress;
  if (progress?.processed != null && progress.selected != null) {
    return `${formatCount(progress.processed)} of ${formatCount(progress.selected)} transactions`;
  }
  const windows = session.inspection?.marketWindows;
  if (!windows?.length) return 'Not downloaded yet';
  const selected = windows.reduce((sum, window) => sum + window.selected, 0);
  if (windows.some((window) => window.detailGaps === null)) {
    return `${formatCount(selected)} transactions selected`;
  }
  const processed = windows.reduce(
    (sum, window) => sum + Math.max(0, window.selected - (window.detailGaps ?? 0)),
    0,
  );
  return `${formatCount(processed)} of ${formatCount(selected)} transactions`;
}

export function CaptureStepper({ session }: { session: CaptureSession }) {
  const summary = session.summary;
  if (!summary) return null;
  const mint = session.wallet?.mint ?? session.inspection?.marketWindows[0]?.mint;
  const detail = {
    WALLET: `${session.inspection?.walletSummary.buys ?? 0} buys found${mint ? ` · ${mintLabel(mint)} selected` : ''}`,
    MARKETS: marketDetail(session),
    RATES:
      summary.steps.find((step) => step.stage === 'MARKETS')?.state !== 'DONE'
        ? 'Next after markets'
        : `${session.inspection?.ratesSummary.recorded ?? session.inspection?.ratesSummary.minutes ?? 0} minutes recorded`,
    DATASET: 'Offline check and packaging; no requests',
  };
  return (
    <Card component="section" aria-label="Four capture steps">
      <SimpleGrid cols={{ base: 1, sm: 4 }} spacing="md">
        {summary.steps.map((step, index) => (
          <Group key={step.stage} gap="sm" align="flex-start" wrap="nowrap">
            <Badge
              size="lg"
              circle
              color={step.state === 'DONE' ? 'teal' : step.state === 'STOPPED' ? 'orange' : 'gray'}
              variant="light"
            >
              {step.state === 'DONE' ? '✓' : index + 1}
            </Badge>
            <Stack gap={3} style={{ minWidth: 0 }}>
              <Text fw={600} size="sm">
                {stageLabels[step.stage]}
              </Text>
              <Text size="xs">{stepState(summary, step)}</Text>
              <Text size="xs" c="dimmed">
                {detail[step.stage]}
              </Text>
            </Stack>
          </Group>
        ))}
      </SimpleGrid>
    </Card>
  );
}

/** The token colour of a status tone, for the edge of the "Now" card. */
const toneColors: Record<StatusTone, string> = {
  success: 'var(--console-gain)',
  ready: 'var(--console-gain)',
  failure: 'var(--console-loss)',
  running: 'var(--console-info)',
  attention: 'var(--console-rejected)',
  pending: 'var(--console-line-strong)',
  neutral: 'var(--console-line-strong)',
};

export function CaptureOverview({
  session,
  config,
  onEnableRecovery,
}: {
  session: CaptureSession;
  config: CaptureConfig;
  onEnableRecovery?: () => void;
}) {
  const next = useNextAction(session, config);
  const pacing = useQuery({
    queryKey: ['usage', 'capture-pacing'],
    queryFn: ({ signal }) => setupApi.usage('LAST_HOUR', undefined, signal),
    staleTime: 60_000,
  });
  const summary = session.summary;
  if (!summary) return null;
  const interval = pacing.data?.pacing.find(
    (item) => item.provider === 'SOLANA_RPC',
  )?.minRequestIntervalMillis;
  const copy = nowCopy(summary, session.operation);
  const color = toneColors[statusTones[summary.status]];
  return (
    <Card
      component="section"
      aria-label="Now"
      padding="lg"
      style={{
        borderColor: 'var(--console-line-strong)',
        borderLeft: `3px solid ${color}`,
        background: `color-mix(in srgb, ${color} 6%, var(--console-panel))`,
      }}
    >
      <Stack gap="md">
        <span className="console-label">Now</span>
        <Title order={3} fz={22}>
          {copy.headline}
        </Title>
        <Text size="sm">{copy.body}</Text>
        <Group align="center">
          <Button
            onClick={next.run}
            disabled={next.disabled || !!next.pendingCommand}
            loading={next.isPending}
            size="md"
          >
            {next.label}
          </Button>
          {summary.status === 'STOPPED_LIMIT' &&
            !session.operation?.recovery?.enabled &&
            onEnableRecovery && (
              <Button variant="default" onClick={onEnableRecovery}>
                Turn on automatic resume
              </Button>
            )}
          <Text size="xs" c="dimmed">
            {nextActionHint(summary, interval).replace(/^At most /, 'Up to ')}
          </Text>
        </Group>
        {next.unavailable && (
          <Text size="sm" c="orange">
            {next.unavailable}
          </Text>
        )}
        <CodeText code={summary.reasonCode} />
        <CommandError
          error={next.error}
          uncertain={!!next.pendingCommand && !next.isPending}
          retry={next.retry}
        />
      </Stack>
    </Card>
  );
}
