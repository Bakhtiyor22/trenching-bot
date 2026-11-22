import { Card, Group, ScrollArea, Stack, Table, Text, UnstyledButton } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import type { AutomationEvaluation } from '../../api/automation';
import { pnlScale } from './automationModel';
import {
  EvaluationChip,
  PnlBarCell,
  PnlBarLegend,
  SignalsMeter,
  SIGNALS_NEEDED,
  SolValue,
  VariationLink,
} from './AutomationShared';
import { useVariationBook } from './useVariationBook';

/** Below this width the scoreboard becomes one card per variation. */
const NARROW = '(max-width: 48em)';
/** The chosen variation: the raised tone with the accent edge. */
const SELECTED = {
  background: 'var(--console-raised)',
  boxShadow: 'inset 3px 0 0 var(--mantine-primary-color-filled)',
} as const;

function VariationName({
  evaluation,
  change,
}: {
  evaluation: AutomationEvaluation;
  change: string | null;
}) {
  return (
    <span>
      <Text span ff="monospace" fw={600} size="sm">
        {evaluation.label}
      </Text>
      {change && (
        <Text span size="sm" c="dimmed">
          {' '}
          {change}
        </Text>
      )}
    </span>
  );
}

/** "Settings & runs →", the way from a scoreboard row to the variation's own page. */
function OpenVariation({ label }: { label: string }) {
  return (
    <VariationLink label={label} size="xs">
      Settings &amp; runs →
    </VariationLink>
  );
}

/**
 * Every variation of one trading day's 7-day window, in the backend's order (Success › No success › No result ›
 * Pending, then worst case descending). The P&L bars share one scale, so their lengths compare across rows; a pending
 * row keeps its counts so far, faded. Choosing a row selects it for the trend view; its link opens the variation.
 */
export function Scoreboard({
  evaluations,
  selectedLabel,
  onSelect,
}: {
  evaluations: AutomationEvaluation[];
  selectedLabel?: string | null;
  onSelect?: (label: string) => void;
}) {
  const narrow = useMediaQuery(NARROW) ?? false;
  const book = useVariationBook();
  const scale = pnlScale(evaluations.flatMap((e) => [e.worstCasePnl, e.realizedPnl]));
  if (narrow) {
    return (
      <Stack
        gap="xs"
        component="ol"
        style={{ listStyle: 'none', margin: 0, padding: 0 }}
        aria-label="Scoreboard"
      >
        <PnlBarLegend />
        {evaluations.map((e) => (
          <Card
            key={e.label}
            component="li"
            padding="sm"
            aria-current={e.label === selectedLabel || undefined}
            style={e.label === selectedLabel ? SELECTED : undefined}
          >
            <UnstyledButton
              onClick={() => onSelect?.(e.label)}
              aria-label={`Show ${e.label} over time`}
              style={{ display: 'block', width: '100%' }}
            >
              <Stack gap={8}>
                <Group justify="space-between" wrap="nowrap" gap="xs">
                  <VariationName evaluation={e} change={book.describe(e.label)} />
                  <EvaluationChip status={e.status} pendingDay={e.pendingDay} />
                </Group>
                <PnlBarCell
                  worst={e.worstCasePnl}
                  realized={e.realizedPnl}
                  scale={scale}
                  faded={e.status === 'NO_RESULT' || e.status === 'PENDING'}
                  label={e.label}
                />
                <Group justify="space-between" gap="xs">
                  <Text size="xs" c="dimmed">
                    Worst <SolValue value={e.worstCasePnl} strong /> · realized{' '}
                    <SolValue value={e.realizedPnl} />
                  </Text>
                  <Text size="xs" c="dimmed">
                    {e.signals} signals ({SIGNALS_NEEDED} needed)
                    {e.unscored > 0 && ` · ${e.unscored} unscored`}
                  </Text>
                </Group>
              </Stack>
            </UnstyledButton>
            <OpenVariation label={e.label} />
          </Card>
        ))}
      </Stack>
    );
  }
  return (
    <Stack gap="xs">
      <PnlBarLegend />
      <ScrollArea type="auto">
        <Table miw={880} verticalSpacing="xs" aria-label="Scoreboard">
          <Table.Thead>
            <Table.Tr>
              <Table.Th className="console-sticky">Variation</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th w={280}>Net P&amp;L, SOL · 0 in the middle</Table.Th>
              <Table.Th ta="right">Worst case</Table.Th>
              <Table.Th ta="right">Realized</Table.Th>
              <Table.Th>Signals (need {SIGNALS_NEEDED})</Table.Th>
              <Table.Th ta="right">Unscored</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {evaluations.map((e) => (
              <Table.Tr
                key={e.label}
                onClick={() => onSelect?.(e.label)}
                style={{
                  cursor: onSelect ? 'pointer' : undefined,
                  ...(e.label === selectedLabel
                    ? SELECTED
                    : e.label === book.baseline
                      ? { background: 'var(--console-neutral-soft)' }
                      : undefined),
                }}
                aria-selected={onSelect ? e.label === selectedLabel : undefined}
              >
                <Table.Th
                  scope="row"
                  fw={400}
                  className="console-sticky"
                  style={e.label === selectedLabel ? SELECTED : undefined}
                >
                  <Stack gap={2} align="flex-start">
                    {onSelect ? (
                      <UnstyledButton
                        onClick={() => onSelect(e.label)}
                        aria-label={`Show ${e.label} over time`}
                      >
                        <VariationName evaluation={e} change={book.describe(e.label)} />
                      </UnstyledButton>
                    ) : (
                      <VariationName evaluation={e} change={book.describe(e.label)} />
                    )}
                    <OpenVariation label={e.label} />
                  </Stack>
                </Table.Th>
                <Table.Td>
                  <EvaluationChip status={e.status} pendingDay={e.pendingDay} />
                </Table.Td>
                <Table.Td>
                  <PnlBarCell
                    worst={e.worstCasePnl}
                    realized={e.realizedPnl}
                    scale={scale}
                    faded={e.status === 'NO_RESULT' || e.status === 'PENDING'}
                    label={e.label}
                  />
                </Table.Td>
                <Table.Td ta="right">
                  <SolValue value={e.worstCasePnl} strong />
                </Table.Td>
                <Table.Td ta="right">
                  <SolValue value={e.realizedPnl} />
                </Table.Td>
                <Table.Td>
                  <SignalsMeter signals={e.signals} />
                </Table.Td>
                <Table.Td ta="right" ff="monospace">
                  {e.unscored}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
    </Stack>
  );
}
