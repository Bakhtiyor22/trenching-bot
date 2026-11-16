import { Anchor, Button, Group, Stack, Table, Text, Timeline, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import {
  ITEM_STATES,
  STEPS,
  isItemHistoryUnavailable,
  useAutomationItemEvents,
  type AutomationItemEvent,
  type AutomationItemState,
  type AutomationStep,
} from '../../api/automation';
import { capturesApi } from '../../api/captures';
import { CodeText } from '../../components/PlainLanguage';
import { QueryView } from '../../components/QueryView';
import { codeLabel } from '../../lib/codes';
import { formatBytes, formatCount, formatDuration, humanizeCode } from '../../lib/format';
import { paths } from '../../paths';
import { reasonText } from '../captures/captureReasons';
import { captureKeys, isCaptureOperationActive } from '../captures/captureQueries';
import { itemStateLabels, outcomeLabel, zoneDate, zoneLabel, zoneTime } from './automationModel';
import { RunLink } from './AutomationShared';

/**
 * The selected buy's history: every recorded event in `seq` order, with its time on the automation clock, the time since
 * the event before, and what it did. A capture operation expands to its record from the capture API.
 */

type Tone = 'replayed' | 'progress' | 'issues' | 'skipped';
const TONE_COLOR: Record<Tone, string> = {
  replayed: 'var(--automation-replayed)',
  progress: 'var(--automation-progress)',
  issues: 'var(--automation-issues)',
  skipped: 'var(--automation-skipped)',
};
const FAILED_STATES = new Set(['FAILED', 'ABANDONED']);

function stepName(step: string | null): string | null {
  if (!step) return null;
  return STEPS.includes(step as AutomationStep)
    ? humanizeCode(step).toLowerCase()
    : humanizeCode(step);
}

function stateName(state: string | null): string {
  if (!state) return '—';
  return ITEM_STATES.includes(state as AutomationItemState)
    ? itemStateLabels[state as AutomationItemState]
    : humanizeCode(state);
}

function failureText(event: AutomationItemEvent): string | null {
  return (
    event.failureMessage ??
    (event.failureCode ? (reasonText(event.failureCode) ?? codeLabel(event.failureCode)) : null)
  );
}

function detailText(event: AutomationItemEvent, key: string): string | null {
  const value = event.detail?.[key];
  return typeof value === 'string' ? value : null;
}

/** What an event did, in words; an unknown kind reads as its humanized code. */
function eventTitle(event: AutomationItemEvent): string {
  const step = stepName(event.step);
  const attempt = event.attempt && event.attempt > 1 ? ` (attempt ${event.attempt})` : '';
  switch (event.kind) {
    case 'HISTORY_STARTED':
      return `History starts: ${stateName(event.toState).toLowerCase()}${step ? ` at the ${step} step` : ''}`;
    case 'STATE_CHANGED':
      if (!event.fromState) return `Found as ${stateName(event.toState).toLowerCase()}`;
      if (event.fromState === event.toState) return `Moved to the ${step ?? 'next'} step`;
      return `${stateName(event.fromState)} → ${stateName(event.toState)}${step ? ` at the ${step} step` : ''}`;
    case 'OPERATION_STARTED':
      return `Started the ${step ?? ''} capture${attempt}`;
    case 'OPERATION_ENDED': {
      const state = detailText(event, 'operationState');
      return `Finished the ${step ?? ''} capture${attempt}${state ? `: ${humanizeCode(state).toLowerCase()}` : ''}`;
    }
    case 'ATTEMPT_FAILED':
      return `Attempt ${event.attempt ?? '?'} of the ${step ?? ''} step failed`;
    case 'RETRY_REQUESTED':
      return `Retry requested${step ? ` from the ${step} step` : ''}`;
    case 'SWEEP_ENTRY_RECORDED': {
      const label = detailText(event, 'label');
      const outcome = detailText(event, 'outcome');
      return `Replayed ${label ?? 'a variation'}${outcome ? `: ${outcomeLabel(outcome)}` : ''}`;
    }
    default:
      return `${humanizeCode(event.kind)}${step ? ` at the ${step} step` : ''}`;
  }
}

function eventTone(event: AutomationItemEvent): Tone {
  if (event.kind === 'ATTEMPT_FAILED' || FAILED_STATES.has(event.toState ?? '')) return 'issues';
  if (event.kind === 'HISTORY_STARTED') return 'skipped';
  if (event.kind === 'OPERATION_STARTED' || event.kind === 'RETRY_REQUESTED') return 'progress';
  if (event.kind === 'OPERATION_ENDED' && detailText(event, 'operationState') !== 'SUCCEEDED')
    return 'issues';
  return 'replayed';
}

/** The facts an event carries, as one line: step, attempt, requests, who, and how long its operation took. */
function eventFacts(event: AutomationItemEvent, startedAt: string | undefined): string[] {
  const facts: string[] = [];
  if (event.step) facts.push(`step ${stepName(event.step)}`);
  if (event.attempt != null) facts.push(`attempt ${event.attempt}`);
  if (event.requestsUsed != null)
    facts.push(
      `${formatCount(event.requestsUsed)} ${event.requestsUsed === 1 ? 'request' : 'requests'}`,
    );
  if (startedAt) facts.push(`took ${formatDuration(Date.parse(event.at) - Date.parse(startedAt))}`);
  if (event.actor) facts.push(`by ${event.actor}`);
  return facts;
}

function EventTime({
  event,
  previous,
  zone,
}: {
  event: AutomationItemEvent;
  previous: AutomationItemEvent | undefined;
  zone: string;
}) {
  const date = zoneDate(event.at, zone);
  const showDate = !previous || zoneDate(previous.at, zone) !== date;
  const gap = previous ? Date.parse(event.at) - Date.parse(previous.at) : null;
  return (
    <Group gap={8} wrap="wrap">
      <Text size="xs" ff="monospace" c="dimmed" title={`${event.at} (UTC)`}>
        <time dateTime={event.at}>
          {showDate ? `${date}, ` : ''}
          {zoneTime(event.at, zone, true)}
        </time>
      </Text>
      {gap !== null && (
        <Text size="xs" ff="monospace" c="dimmed">
          +{formatDuration(gap)}
        </Text>
      )}
    </Group>
  );
}

/** A capture operation's record, read from the capture API when expanded. */
function OperationDetails({
  sessionId,
  operationId,
  zone,
}: {
  sessionId: string;
  operationId: string;
  zone: string;
}) {
  const operation = useQuery({
    queryKey: [...captureKeys.session(sessionId), 'operation', operationId],
    queryFn: ({ signal }) => capturesApi.operation(sessionId, operationId, signal),
    refetchInterval: (query) =>
      isCaptureOperationActive(query.state.data?.state) ? CAPTURE_DETAIL_POLL_MS : false,
    refetchIntervalInBackground: false,
  });
  return (
    <QueryView
      query={operation}
      loadingLabel="Loading the operation…"
      errorTitle="Operation unavailable"
    >
      {(op) => {
        const rows: Array<[string, ReactNode]> = [
          ['State', humanizeCode(op.state)],
          ['Step', humanizeCode(op.step)],
          [
            'Ran',
            `${zoneTime(op.startedAt, zone, true)} → ${op.endedAt ? zoneTime(op.endedAt, zone, true) : 'still running'} ${zoneLabel(zone)}`,
          ],
          [
            'Requests',
            `${formatCount(op.progress?.operationRequests)} of ${formatCount(op.budget.requestBudget)} allowed`,
          ],
          [
            'Downloaded',
            `${formatBytes(op.progress?.operationBytes)} of ${formatBytes(op.budget.byteBudget)} allowed`,
          ],
          [
            'Records',
            `${formatCount(op.progress?.processed)} read, ${formatCount(op.progress?.selected)} kept`,
          ],
        ];
        if (op.message || op.code) rows.push(['Error', op.message ?? codeLabel(op.code!)]);
        return (
          <Stack gap={6} mt={6}>
            <Table
              verticalSpacing={4}
              withRowBorders={false}
              aria-label={`Capture operation ${operationId}`}
            >
              <Table.Tbody>
                {rows.map(([label, value]) => (
                  <Table.Tr key={label}>
                    <Table.Th w={110} fw={400} c="dimmed" fz="xs">
                      {label}
                    </Table.Th>
                    <Table.Td fz="xs" ff="monospace" style={{ overflowWrap: 'anywhere' }}>
                      {value}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
            <CodeText code={op.code} />
            <Anchor component={Link} to={paths.capture(sessionId)} size="xs">
              Open the capture and its evidence
            </Anchor>
          </Stack>
        );
      }}
    </QueryView>
  );
}

/** A running capture operation's record is refreshed this often while expanded. */
const CAPTURE_DETAIL_POLL_MS = 5_000;

function TraceEvent({
  event,
  previous,
  startedAt,
  firstOfOperation,
  zone,
}: {
  event: AutomationItemEvent;
  previous: AutomationItemEvent | undefined;
  startedAt: string | undefined;
  firstOfOperation: boolean;
  zone: string;
}) {
  const [open, setOpen] = useState(false);
  const failure = failureText(event);
  const facts = eventFacts(event, startedAt);
  const runId = event.kind === 'SWEEP_ENTRY_RECORDED' ? detailText(event, 'runId') : null;
  const operation =
    firstOfOperation && event.captureSessionId && event.operationId
      ? { sessionId: event.captureSessionId, operationId: event.operationId }
      : null;
  return (
    <Stack gap={2}>
      <EventTime event={event} previous={previous} zone={zone} />
      {event.kind === 'HISTORY_STARTED' && (
        <Text size="sm">
          History recorded from {zoneDate(event.at, zone)}, {zoneTime(event.at, zone)}{' '}
          {zoneLabel(zone)}; earlier steps were not recorded.
        </Text>
      )}
      {facts.length > 0 && (
        <Text size="xs" c="dimmed">
          {facts.join(' · ')}
        </Text>
      )}
      {failure && (
        <Text size="sm" c="red" style={{ overflowWrap: 'anywhere' }}>
          {failure}
        </Text>
      )}
      {event.failureCode && event.failureCode !== failure && <CodeText code={event.failureCode} />}
      {event.note && (
        <Text size="xs" c="dimmed">
          Note: {event.note}
        </Text>
      )}
      {runId && (
        <Group gap={6}>
          <Text size="xs" c="dimmed">
            Run
          </Text>
          <RunLink runId={runId} />
        </Group>
      )}
      {operation && (
        <>
          <Button
            variant="subtle"
            size="compact-xs"
            w="fit-content"
            px={0}
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
          >
            {open ? 'Hide capture details' : 'Capture details'}
          </Button>
          {open && <OperationDetails {...operation} zone={zone} />}
        </>
      )}
    </Stack>
  );
}

export function TraceTimeline({
  itemId,
  zone,
  live,
}: {
  itemId: string;
  zone: string;
  /** True while the buy can still change; the history then refreshes with it. */
  live: boolean;
}) {
  const events = useAutomationItemEvents(itemId, { poll: live });
  const unavailable = events.data === undefined && isItemHistoryUnavailable(events.error);
  if (unavailable)
    return (
      <Stack gap="xs" component="section" aria-label="History of this buy">
        <Title order={4} fz={13}>
          History
        </Title>
        <Text size="sm" c="dimmed">
          Step history is not available from this backend yet.
        </Text>
      </Stack>
    );
  return (
    <Stack gap="xs" component="section" aria-label="History of this buy">
      <Group gap="sm" justify="space-between">
        <Title order={4} fz={13}>
          History
        </Title>
        <Text size="xs" c="dimmed">
          Times {zoneLabel(zone)} · each step, request count and error as it happened
        </Text>
      </Group>
      <QueryView
        query={events}
        loadingLabel="Loading the history…"
        errorTitle="History unavailable"
      >
        {({ events: list }) => {
          if (list.length === 0)
            return (
              <Text size="sm" c="dimmed">
                No steps recorded yet.
              </Text>
            );
          const ordered = [...list].sort((a, b) => a.seq - b.seq);
          const startedAt = new Map<string, string>();
          const seenOperations = new Set<string>();
          return (
            <Timeline
              active={ordered.length - 1}
              bulletSize={12}
              lineWidth={2}
              role="list"
              aria-label="Steps"
            >
              {ordered.map((event, i) => {
                const opKey = event.operationId
                  ? `${event.captureSessionId}/${event.operationId}`
                  : null;
                const first = opKey !== null && !seenOperations.has(opKey);
                if (opKey) seenOperations.add(opKey);
                if (opKey && event.kind === 'OPERATION_STARTED') startedAt.set(opKey, event.at);
                const ended =
                  opKey && (event.kind === 'OPERATION_ENDED' || event.kind === 'ATTEMPT_FAILED')
                    ? startedAt.get(opKey)
                    : undefined;
                return (
                  <Timeline.Item
                    key={event.seq}
                    role="listitem"
                    title={
                      <Text size="sm" fw={600}>
                        {eventTitle(event)}
                      </Text>
                    }
                    color={TONE_COLOR[eventTone(event)]}
                  >
                    <TraceEvent
                      event={event}
                      previous={ordered[i - 1]}
                      startedAt={ended}
                      firstOfOperation={first}
                      zone={zone}
                    />
                  </Timeline.Item>
                );
              })}
            </Timeline>
          );
        }}
      </QueryView>
    </Stack>
  );
}
