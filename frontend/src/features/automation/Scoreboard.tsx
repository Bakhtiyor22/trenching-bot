import { Card, Group, ScrollArea, Stack, Table, Text, UnstyledButton } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import type { AutomationEvaluation } from '../../api/automation';
import { BASELINE_LABEL, pnlScale, variationChange } from './automationModel';
import { PnlBarCell, PnlBarLegend, SignalsMeter, SolValue, StatusChip } from './AutomationShared';

/** Below this width the scoreboard becomes one card per variation. */
const NARROW = '(max-width: 48em)';

function VariationName({ evaluation }: { evaluation: AutomationEvaluation }) {
  const change = variationChange(evaluation.planId, evaluation.label);
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

/**
 * Every variation of one night's window, in the backend's order (Success › No success › No result, then worst case
 * descending). The P&L bars share one scale, so their lengths compare across rows. Choosing a row selects it for the
 * trend view.
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
          >
            <UnstyledButton
              onClick={() => onSelect?.(e.label)}
              aria-label={`Show ${e.label} over time`}
              style={{ display: 'block', width: '100%' }}
            >
              <Stack gap={8}>
                <Group justify="space-between" wrap="nowrap" gap="xs">
                  <VariationName evaluation={e} />
                  <StatusChip status={e.status} />
                </Group>
                <PnlBarCell
                  worst={e.worstCasePnl}
                  realized={e.realizedPnl}
                  scale={scale}
                  faded={e.status === 'NO_RESULT'}
                  label={e.label}
                />
                <Group justify="space-between" gap="xs">
                  <Text size="xs" c="dimmed">
                    Worst <SolValue value={e.worstCasePnl} strong /> · realized{' '}
                    <SolValue value={e.realizedPnl} />
                  </Text>
                  <Text size="xs" c="dimmed">
                    {e.signals} signals (10 needed){e.unscored > 0 && ` · ${e.unscored} unscored`}
                  </Text>
                </Group>
              </Stack>
            </UnstyledButton>
          </Card>
        ))}
      </Stack>
    );
  }
  return (
    <Stack gap="xs">
      <PnlBarLegend />
      <ScrollArea type="auto">
        <Table miw={880} aria-label="Scoreboard">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Variation</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th w={280}>Net P&amp;L, SOL · 0 in the middle</Table.Th>
              <Table.Th ta="right">Worst case</Table.Th>
              <Table.Th ta="right">Realized</Table.Th>
              <Table.Th>Signals (need 10)</Table.Th>
              <Table.Th ta="right">Unscored</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {evaluations.map((e) => (
              <Table.Tr
                key={e.label}
                onClick={() => onSelect?.(e.label)}
                style={{ cursor: onSelect ? 'pointer' : undefined }}
                bg={
                  e.label === selectedLabel
                    ? 'var(--mantine-primary-color-light)'
                    : e.label === BASELINE_LABEL
                      ? 'var(--mantine-color-default-hover)'
                      : undefined
                }
                aria-selected={onSelect ? e.label === selectedLabel : undefined}
              >
                <Table.Th scope="row" fw={400}>
                  {onSelect ? (
                    <UnstyledButton
                      onClick={() => onSelect(e.label)}
                      aria-label={`Show ${e.label} over time`}
                    >
                      <VariationName evaluation={e} />
                    </UnstyledButton>
                  ) : (
                    <VariationName evaluation={e} />
                  )}
                </Table.Th>
                <Table.Td>
                  <StatusChip status={e.status} />
                </Table.Td>
                <Table.Td>
                  <PnlBarCell
                    worst={e.worstCasePnl}
                    realized={e.realizedPnl}
                    scale={scale}
                    faded={e.status === 'NO_RESULT'}
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
