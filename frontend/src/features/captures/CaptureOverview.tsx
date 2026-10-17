import { Badge, Button, Group, Progress, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router';
import { capturesApi, type CaptureConfig, type CaptureSession } from '../../api/captures';
import { setupApi } from '../../api/setup';
import { ApiError } from '../../api/client';
import { Section } from '../../components/Panels';
import { CodeText, Term } from '../../components/PlainLanguage';
import { Timestamp } from '../../components/DisplayValues';
import { codeLabel, statusLabels } from '../../lib/codes';
import { paths } from '../../paths';
import { captureKeys, useCaptureCommand } from './captureQueries';
import { useNextAction } from './useNextAction';
import { CommandError } from './CaptureShared';

export function CaptureStepper({ session }: { session: CaptureSession }) {
  return (
    <SimpleGrid cols={4} spacing="xs" aria-label="Four capture steps">
      {session.summary?.steps.map((step) => (
        <Stack key={step.stage} gap={4}>
          <Progress
            value={step.state === 'DONE' ? 100 : step.state === 'ACTIVE' ? 50 : 0}
            color={step.state === 'FAILED' ? 'red' : step.state === 'STOPPED' ? 'yellow' : 'teal'}
            aria-label={`${step.stage}: ${step.state}`}
          />
          <Text size="xs">
            {step.stage.toLowerCase()} · {step.state.toLowerCase()}
          </Text>
        </Stack>
      ))}
    </SimpleGrid>
  );
}
export function CaptureOverview({
  session,
  config,
}: {
  session: CaptureSession;
  config: CaptureConfig;
}) {
  const next = useNextAction(session, config);
  const client = useQueryClient();
  const [name, setName] = useState(session.name);
  const [editing, setEditing] = useState(false);
  const pacing = useQuery({
    queryKey: ['usage', 'capture-pacing'],
    queryFn: ({ signal }) => setupApi.usage('LAST_HOUR', undefined, signal),
    staleTime: 60_000,
  });
  const rename = useCaptureCommand<{ requestId: string; name: string }, CaptureSession>(
    `${session.id}:rename`,
    (body) => capturesApi.rename(session.id, body),
    async () => {
      setEditing(false);
      await client.invalidateQueries({ queryKey: captureKeys.all });
    },
  );
  const summary = session.summary;
  const interval = pacing.data?.pacing.find(
    (item) => item.provider === 'SOLANA_RPC',
  )?.minRequestIntervalMillis;
  if (!summary) return null;
  return (
    <Section
      title="What happens next"
      actions={
        <Button variant="subtle" size="xs" onClick={() => setEditing(!editing)}>
          Rename capture
        </Button>
      }
    >
      <Stack>
        {editing && (
          <form
            onSubmit={(event) => {
              event.preventDefault();
              rename.submit({ name: name.trim() });
            }}
          >
            <Group align="flex-start">
              <TextInput
                label="Capture name"
                value={name}
                onChange={(event) => setName(event.currentTarget.value)}
                maxLength={120}
                required
                error={rename.error instanceof ApiError ? rename.error.fields.name : undefined}
              />
              <Button
                type="submit"
                mt={24}
                loading={rename.isPending}
                disabled={!!rename.pendingCommand}
              >
                Save name
              </Button>
            </Group>
            {rename.error instanceof ApiError && rename.error.suggestion && (
              <Button
                variant="subtle"
                onClick={() =>
                  setName(
                    rename.error instanceof ApiError ? (rename.error.suggestion ?? name) : name,
                  )
                }
              >
                Use suggestion
              </Button>
            )}
            <CommandError
              error={rename.error}
              uncertain={!!rename.pendingCommand && !rename.isPending}
              retry={rename.retry}
            />
          </form>
        )}
        <Group justify="space-between">
          <Stack gap={4}>
            <Badge color={summary.status === 'NEEDS_ATTENTION' ? 'yellow' : 'teal'} variant="light">
              {statusLabels[summary.status]}
            </Badge>
            <Text fw={600}>
              Step {summary.currentStep.index} of 4 · {summary.currentStep.stage.toLowerCase()}
            </Text>
            {summary.reasonCode && <Text size="sm">{codeLabel(summary.reasonCode)}</Text>}
            <CodeText code={summary.reasonCode} />
          </Stack>
          <Button
            onClick={next.run}
            disabled={next.disabled || !!next.pendingCommand}
            loading={next.isPending}
            size="md"
          >
            {next.label}
          </Button>
        </Group>
        {next.unavailable && (
          <Text size="sm" c="yellow">
            {next.unavailable}
          </Text>
        )}
        <CaptureStepper session={session} />
        {summary.progress && (
          <Stack gap={4}>
            <Progress value={Number(summary.progress.percent)} aria-label="Capture progress" />
            <Text size="sm">
              {summary.progress.processed.toLocaleString()} of{' '}
              {summary.progress.selected.toLocaleString()} transactions · {summary.progress.percent}
              %
            </Text>
          </Stack>
        )}
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {summary.nextAction.maxRequests
              ? `Up to ${summary.nextAction.maxRequests.toLocaleString()} requests${interval ? ` · at least ${Math.ceil((summary.nextAction.maxRequests * interval) / 1000)} seconds at the configured pacing` : ''}`
              : 'No request allowance for this action.'}
          </Text>
          <Button
            component={Link}
            to={`${paths.usage}?captureId=${encodeURIComponent(session.id)}`}
            variant="subtle"
          >
            API usage
          </Button>
        </Group>
        {summary.lastProgressAt && (
          <Text size="xs" c="dimmed">
            Last progress <Timestamp iso={summary.lastProgressAt} />
          </Text>
        )}
        <Group gap="md">
          <Text size="xs">
            {summary.cases.total} tokens · {summary.cases.ready} ready · {summary.cases.blocked}{' '}
            blocked · {summary.cases.downloading} downloading
          </Text>
          <Term>Market window</Term>
          <Term>Rates</Term>
          <Term>Recovery</Term>
        </Group>
        <CommandError
          error={next.error}
          uncertain={!!next.pendingCommand && !next.isPending}
          retry={next.retry}
        />
      </Stack>
    </Section>
  );
}
