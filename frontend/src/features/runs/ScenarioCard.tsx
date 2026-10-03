import { Alert, Badge, Card, Code, Divider, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import type { Scenario } from '../../api/types';
import { Decimal, Pnl } from '../../components/DisplayValues';
import { Stat } from '../../components/Panels';
import { formatSlot, humanizeCode, offsetLabel } from '../../lib/format';

function Reason({ code }: { code: string | null }) {
  return (
    <>
      {humanizeCode(code)} {code && <Code fz="xs">{code}</Code>}
    </>
  );
}

/** One entry scenario (+1/+2/+3) of a source buy: fill or rejection, then exit and P&L. */
export function ScenarioCard({ scenario }: { scenario: Scenario }) {
  const { fill, position } = scenario;
  const close = position?.close ?? null;
  const offset = offsetLabel(scenario.entryOffset);
  const filled = scenario.status === 'FILLED' && fill;

  return (
    <Card withBorder radius="md" padding="md" component="article" aria-label={`Scenario ${offset}`}>
      <Group justify="space-between" mb="xs" wrap="nowrap">
        <Group gap="xs">
          <Badge size="lg" variant="filled" color="dark" radius="sm">
            {offset}
          </Badge>
          <Stack gap={0}>
            <Text fw={600} size="sm">
              Entry at source slot {offset}
            </Text>
            <Text size="xs" c="dimmed">
              Target slot {formatSlot(scenario.targetSlot)} · source {formatSlot(scenario.sourceSlot)}
            </Text>
          </Stack>
        </Group>
        <Badge color={filled ? 'teal' : 'red'} variant="light">
          {scenario.status}
        </Badge>
      </Group>

      {!filled ? (
        <Alert color="red" variant="light" p="xs" title="Rejected">
          <Text size="sm">
            <Reason code={scenario.rejectionReason} />
          </Text>
        </Alert>
      ) : (
        <Stack gap="sm">
          <Divider label="Entry" labelPosition="left" />
          <SimpleGrid cols={2} spacing="xs" verticalSpacing="xs">
            <Stat label="Filled at slot" value={<Text size="sm">{formatSlot(fill.actualSlot)}</Text>} />
            <Stat label="Execution price" value={<Decimal value={fill.executionPrice} />} />
            <Stat label="Quantity" value={<Decimal value={fill.quantity} />} />
            <Stat label="Fee" value={<Decimal value={fill.fee} />} />
            <Stat label="Cost basis" value={<Decimal value={fill.costBasis} />} />
            <Stat label="Notional" value={<Decimal value={scenario.notional} />} />
          </SimpleGrid>

          <Divider
            label={
              <Group gap={6}>
                Exit
                {position && (
                  <Badge size="xs" variant="light" color={position.state === 'OPEN' ? 'yellow' : 'gray'}>
                    {position.state}
                  </Badge>
                )}
              </Group>
            }
            labelPosition="left"
          />
          {close ? (
            <SimpleGrid cols={2} spacing="xs" verticalSpacing="xs">
              <Stat label="Exit slot" value={<Text size="sm">{formatSlot(close.slot)}</Text>} />
              <Stat label="Exit price" value={<Decimal value={close.executionPrice} />} />
              <Stat label="Gross proceeds" value={<Decimal value={close.grossProceeds} />} />
              <Stat label="Exit fee" value={<Decimal value={close.fee} />} />
              <Stat label="Net proceeds" value={<Decimal value={close.netProceeds} />} />
              <Stat label="Realized P&L" value={<Pnl value={close.realizedPnl} size="md" />} />
            </SimpleGrid>
          ) : (
            <Alert color="yellow" variant="light" p="xs" title="Exit unavailable">
              <Text size="sm">
                The position stays open:{' '}
                <Reason code={scenario.exitUnavailableReason} />
              </Text>
            </Alert>
          )}
        </Stack>
      )}
    </Card>
  );
}
