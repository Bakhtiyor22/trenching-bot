import {
  Badge,
  Box,
  Card,
  Group,
  Kbd,
  ScrollArea,
  Stack,
  Text,
  UnstyledButton,
} from '@mantine/core';
import type { Scenario } from '../../api/types';
import { Section } from '../../components/Panels';
import { formatDecimal, pnlColor } from '../../lib/decimal';
import { formatSlot, shortId } from '../../lib/format';
import { terminal } from '../../theme';
import { scenarioOutcome, type ScenarioOutcome } from './scenarioOutcome';
import { runLanes, scenarioEvents } from './scenarioStory';

/** Lane grid: token, track, result. */
const LANE_COLUMNS = '110px minmax(0, 1fr) 150px';
/** Ticks under the run timeline, as fractions of its span. */
const LANE_TICKS = [0, 0.25, 0.5, 0.75, 1];

/** How many cycles ended each way, as one proportional bar with a legend. */
export function OutcomeBar({ scenarios }: { scenarios: Scenario[] }) {
  const counts = new Map<string, { outcome: ScenarioOutcome; count: number }>();
  for (const s of scenarios) {
    const outcome = scenarioOutcome(s);
    const entry = counts.get(outcome.tag) ?? { outcome, count: 0 };
    entry.count += 1;
    counts.set(outcome.tag, entry);
  }
  const parts = [...counts.values()];
  return (
    <Stack gap={8}>
      <Box
        h={10}
        role="img"
        aria-label={parts.map((p) => `${p.count} ${p.outcome.label.toLowerCase()}`).join(', ')}
        style={{
          display: 'flex',
          gap: 2,
          borderRadius: 3,
          overflow: 'hidden',
          background: terminal.neutral,
        }}
      >
        {parts.map((p) => (
          <Box key={p.outcome.tag} style={{ flex: p.count, background: p.outcome.hex }} />
        ))}
      </Box>
      <Group gap="md">
        {parts.map((p) => (
          <Text key={p.outcome.tag} size="xs" ff="monospace">
            <span style={{ color: p.outcome.hex }}>●</span> {p.count}{' '}
            {p.outcome.label.toLowerCase()}
          </Text>
        ))}
      </Group>
    </Stack>
  );
}

function pnlText(pnl: string, unit: string): string {
  return `${formatDecimal(pnl)} ${unit}`;
}

/** The run's cycles as a pickable list; the selected one drives the chart, its story and the highlighted card. */
export function CycleList({
  scenarios,
  selected,
  onSelect,
  unit,
}: {
  scenarios: Scenario[];
  selected: number;
  onSelect: (index: number) => void;
  unit: string;
}) {
  return (
    <Card padding={0} component="nav" aria-label="Cycles">
      <Group
        justify="space-between"
        px="md"
        py="sm"
        style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}
      >
        <Text fw={600} fz={15}>
          Cycles
        </Text>
        <Text size="xs" c="dimmed">
          <Kbd>J</Kbd> <Kbd>K</Kbd> to step
        </Text>
      </Group>
      <ScrollArea.Autosize mah={560} type="auto">
        {scenarios.map((s, index) => {
          const outcome = scenarioOutcome(s);
          const active = index === selected;
          return (
            <UnstyledButton
              key={s.intentId}
              className="console-row-button"
              onClick={() => onSelect(index)}
              aria-pressed={active}
              aria-label={`Cycle ${shortId(s.pair.base)}: ${outcome.label}`}
              px="md"
              py={10}
              style={{
                borderBottom: '1px solid var(--mantine-color-default-border)',
                borderLeft: `3px solid ${active ? terminal.accent : 'transparent'}`,
                background: active ? 'var(--mantine-color-default-hover)' : undefined,
              }}
            >
              <Group gap={8} wrap="nowrap">
                <Badge size="sm" variant="filled" color={outcome.color} ff="monospace" miw={44}>
                  {outcome.tag}
                </Badge>
                <Text ff="monospace" size="sm" style={{ flex: 1 }}>
                  {shortId(s.pair.base)}
                </Text>
                {outcome.pnl ? (
                  <Text ff="monospace" size="sm" fw={600} c={pnlColor(outcome.pnl)}>
                    {pnlText(outcome.pnl, unit)}
                  </Text>
                ) : (
                  <Text size="xs" c="dimmed">
                    {outcome.label}
                  </Text>
                )}
              </Group>
              <Text size="xs" c="dimmed" mt={4}>
                First buy at slot {formatSlot(s.sourceSlot)}
                {s.trigger ? ` · dip +${formatSlot(s.trigger.slot - s.sourceSlot)}` : ''}
                {s.position?.close
                  ? ` · exit +${formatSlot(s.position.close.slot - s.sourceSlot)}`
                  : ''}
              </Text>
            </UnstyledButton>
          );
        })}
      </ScrollArea.Autosize>
    </Card>
  );
}

/** The selected cycle, slot by slot. */
export function CycleStory({ scenario, unit }: { scenario: Scenario; unit: string }) {
  const events = scenarioEvents(scenario, unit);
  return (
    <Section
      title="Event timeline"
      hint={`Token ${shortId(scenario.pair.base)}, slot by slot. Tiny prices read 0.0₇8643 for 0.00000008643.`}
    >
      <Stack gap={0}>
        {events.map((e, i) => (
          <Box
            key={`${e.slot}-${e.title}`}
            style={{
              display: 'grid',
              gridTemplateColumns: '72px 14px minmax(0, 1fr)',
              gap: 10,
              alignItems: 'start',
            }}
          >
            <Text size="xs" c="dimmed" ff="monospace" ta="right" pt={2}>
              {formatSlot(e.slot)}
            </Text>
            <Box
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                alignSelf: 'stretch',
              }}
            >
              <Box
                w={10}
                h={10}
                mt={5}
                style={{ borderRadius: '50%', background: e.color, flex: 'none' }}
              />
              {i < events.length - 1 && (
                <Box w={1} style={{ flex: 1, background: 'var(--mantine-color-default-border)' }} />
              )}
            </Box>
            <Stack gap={0} pb="sm">
              <Text size="sm" fw={500}>
                {e.title}
              </Text>
              <Text size="xs" c="dimmed" ff="monospace">
                {e.detail}
              </Text>
            </Stack>
          </Box>
        ))}
      </Stack>
    </Section>
  );
}

/** Every cycle on one axis of slots after its own first buy: waiting for the dip, then holding until the exit. */
export function RunLanes({
  scenarios,
  selected,
  onSelect,
  unit,
}: {
  scenarios: Scenario[];
  selected: number;
  onSelect: (index: number) => void;
  unit: string;
}) {
  const { lanes, span } = runLanes(scenarios);
  const pct = (offset: number) => `${(offset / span) * 100}%`;
  return (
    <Section
      title="Run timeline"
      hint="Every cycle aligned on its own first buy. Grey: waiting for the dip; colored: holding the position; dashed: no entry. Click a lane to inspect it."
    >
      <Stack gap={4}>
        {lanes.map((lane, index) => {
          const holdEnd = lane.exit ?? (lane.fill != null ? span : null);
          return (
            <UnstyledButton
              key={lane.intentId}
              className="console-row-button"
              onClick={() => onSelect(index)}
              aria-label={`Inspect cycle ${lane.token}`}
              px={8}
              py={6}
              style={{
                borderRadius: 6,
                outline: index === selected ? `1px solid ${terminal.accent}` : undefined,
              }}
            >
              <Box
                style={{
                  display: 'grid',
                  gridTemplateColumns: LANE_COLUMNS,
                  gap: 12,
                  alignItems: 'center',
                }}
              >
                <Text size="xs" ff="monospace">
                  {lane.token}
                </Text>
                <Box pos="relative" h={18}>
                  <Box
                    pos="absolute"
                    left={0}
                    right={0}
                    top={8}
                    h={2}
                    style={{ background: 'var(--mantine-color-default-border)' }}
                  />
                  {lane.dip == null ? (
                    <Box
                      pos="absolute"
                      top={8}
                      h={2}
                      left={0}
                      right={0}
                      style={{
                        background: `repeating-linear-gradient(90deg, ${terminal.dimmed} 0 4px, transparent 4px 8px)`,
                      }}
                    />
                  ) : (
                    <Box
                      pos="absolute"
                      top={5}
                      h={8}
                      left={0}
                      w={pct(lane.fill ?? lane.dip)}
                      style={{ background: terminal.neutral, borderRadius: 2 }}
                    />
                  )}
                  {lane.fill != null && holdEnd != null && (
                    <Box
                      pos="absolute"
                      top={4}
                      h={10}
                      left={pct(lane.fill)}
                      w={pct(Math.max(holdEnd - lane.fill, span * 0.01))}
                      style={{ background: lane.outcome.hex, borderRadius: 2 }}
                    />
                  )}
                </Box>
                <Text
                  size="xs"
                  ff="monospace"
                  ta="right"
                  c={lane.outcome.pnl ? pnlColor(lane.outcome.pnl) : 'dimmed'}
                >
                  {lane.outcome.pnl
                    ? pnlText(lane.outcome.pnl, unit)
                    : lane.outcome.label.toLowerCase()}
                </Text>
              </Box>
            </UnstyledButton>
          );
        })}
        <Box style={{ display: 'grid', gridTemplateColumns: LANE_COLUMNS, gap: 12 }} px={8}>
          <span />
          <Group justify="space-between">
            {LANE_TICKS.map((f, i) => (
              <Text key={f} size="xs" c="dimmed" ff="monospace">
                +{formatSlot(Math.round(span * f))}
                {i === LANE_TICKS.length - 1 ? ' slots' : ''}
              </Text>
            ))}
          </Group>
          <span />
        </Box>
      </Stack>
    </Section>
  );
}
