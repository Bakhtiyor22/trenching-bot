import {
  Box,
  Button,
  Group,
  ScrollArea,
  Select,
  Stack,
  Table,
  Text,
  useComputedColorScheme,
} from '@mantine/core';
import type { EChartsOption } from 'echarts';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import type { AutomationEvaluation } from '../../api/automation';
import { Chart } from '../../components/Chart';
import { formatDecimal, toChartNumber } from '../../lib/decimal';
import { paths } from '../../paths';
import { terminal } from '../../theme';
import {
  BASELINE_LABEL,
  shortSol,
  statusGrid,
  statusText,
  variationChange,
} from './automationModel';
import { SolValue, StatusChip } from './AutomationShared';

/** The emphasis colours: the chosen variation in the accent, the default in grey, the rest as faint context. */
const COLORS = {
  dark: {
    focus: terminal.accent,
    base: '#A3ABB8',
    other: '#2A343D',
    zero: '#6B7885',
    bar: '#3F8FE8',
    low: '#6B7885',
    axis: terminal.dimmed,
    surface: terminal.panel,
  },
  light: {
    focus: '#B9841F',
    base: '#5C6670',
    other: '#DEE2E6',
    zero: '#868E96',
    bar: '#1C7ED6',
    low: '#ADB5BD',
    axis: '#5C6670',
    surface: '#FFFFFF',
  },
} as const;

const MIN_SIGNALS = 10;

function label(planId: string, l: string): string {
  const change = variationChange(planId, l);
  return change ? `${l} · ${change}` : l;
}

/**
 * Trend option B: one variation's rolling worst-case P&L against the default (V00), the other variations as faint
 * context, with a labelled zero line. Hollow points mark windows under 10 signals (No result) or still pending. The signals sit in a
 * second, separate chart, never on a second y-axis.
 */
export function VariationTrend({
  days,
  evaluations,
  planId,
  labels,
  selected,
  onSelect,
}: {
  /** The trading days shown, any order. */
  days: string[];
  evaluations: AutomationEvaluation[];
  planId: string;
  labels: string[];
  selected: string;
  onSelect: (label: string) => void;
}) {
  const navigate = useNavigate();
  const scheme = useComputedColorScheme('dark');
  const c = COLORS[scheme];
  const [tableView, setTableView] = useState(false);
  const grid = statusGrid(days, evaluations, labels);
  const columns = grid.columns.filter((_, i) => grid.rows.some((row) => row.cells[i] != null));
  const indexOf = new Map(grid.columns.map((col, i) => [col.key, i]));
  const cells = (l: string) => {
    const row = grid.rows.find((r) => r.label === l);
    return columns.map((col) => row?.cells[indexOf.get(col.key)!] ?? null);
  };
  const focus = cells(selected);
  const base = cells(BASELINE_LABEL);
  const x = columns.map((col) => col.short);
  const num = (e: AutomationEvaluation | null) => (e ? toChartNumber(e.worstCasePnl) : null);
  const others = labels.filter((l) => l !== selected && l !== BASELINE_LABEL);

  const lineOption: EChartsOption = {
    animation: false,
    grid: { left: 64, right: 72, top: 16, bottom: 36 },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => {
        const list = params as Array<{ dataIndex: number }>;
        const i = list[0]?.dataIndex ?? 0;
        const f = focus[i];
        const b = base[i];
        const line = (name: string, e: AutomationEvaluation | null | undefined) =>
          e
            ? `${name}: ${shortSol(e.worstCasePnl)} SOL · ${statusText[e.status]} · ${e.signals} signals, ${e.unscored} unscored`
            : `${name}: not scored`;
        return [
          `<b>${columns[i]?.label ?? ''}</b>`,
          line(selected, f),
          selected === BASELINE_LABEL ? '' : line(BASELINE_LABEL, b),
        ]
          .filter(Boolean)
          .join('<br/>');
      },
    },
    xAxis: { type: 'category', data: x, boundaryGap: false, axisLabel: { color: c.axis } },
    yAxis: {
      type: 'value',
      name: 'Worst-case net P&L, SOL',
      nameLocation: 'middle',
      nameGap: 48,
      nameTextStyle: { color: c.axis },
      axisLabel: { color: c.axis, formatter: (v: number) => (v > 0 ? `+${v}` : String(v)) },
    },
    series: [
      ...others.map((l) => ({
        name: l,
        type: 'line' as const,
        data: cells(l).map(num),
        symbol: 'none',
        lineStyle: { width: 1.5, color: c.other },
        itemStyle: { color: c.other },
        emphasis: { disabled: true },
        silent: true,
        z: 1,
      })),
      ...(selected === BASELINE_LABEL
        ? []
        : [
            {
              name: BASELINE_LABEL,
              type: 'line' as const,
              data: base.map(num),
              symbol: 'circle',
              symbolSize: 6,
              lineStyle: { width: 2, color: c.base },
              itemStyle: { color: c.base },
              endLabel: {
                show: true,
                color: c.base,
                fontFamily: 'monospace',
                formatter: BASELINE_LABEL,
              },
              z: 2,
            },
          ]),
      {
        name: selected,
        type: 'line' as const,
        data: focus.map((e) => ({
          value: num(e),
          symbol: 'circle',
          symbolSize: 9,
          itemStyle:
            e && e.signals >= MIN_SIGNALS && e.status !== 'PENDING'
              ? { color: c.focus, borderColor: c.surface, borderWidth: 2 }
              : { color: c.surface, borderColor: c.focus, borderWidth: 2 },
        })),
        lineStyle: { width: 2.5, color: c.focus },
        itemStyle: { color: c.focus },
        endLabel: {
          show: true,
          color: c.focus,
          fontFamily: 'monospace',
          fontWeight: 600,
          formatter: selected,
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: c.zero, type: 'solid', width: 1.2 },
          label: { formatter: '0 SOL', color: c.axis, position: 'insideStartTop' },
          data: [{ yAxis: 0 }],
        },
        z: 3,
      },
    ],
  };
  const signalsOption: EChartsOption = {
    animation: false,
    grid: { left: 64, right: 72, top: 10, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (v) => `${String(v)} signals`,
    },
    xAxis: { type: 'category', data: x, axisLabel: { color: c.axis } },
    yAxis: { type: 'value', minInterval: 1, axisLabel: { color: c.axis } },
    series: [
      {
        name: `${selected} signals`,
        type: 'bar',
        barMaxWidth: 28,
        data: focus.map((e) => ({
          value: e?.signals ?? 0,
          itemStyle: {
            color: e && e.signals >= MIN_SIGNALS ? c.bar : c.low,
            borderRadius: [4, 4, 0, 0],
          },
        })),
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: c.zero, type: 'solid' },
          label: { formatter: '10 needed', color: c.axis },
          data: [{ yAxis: MIN_SIGNALS }],
        },
      },
    ],
  };
  const openNight = (params: unknown) => {
    const i = (params as { dataIndex?: number }).dataIndex;
    const col = i == null ? undefined : columns[i];
    if (col) void navigate(paths.automationDay(col.key));
  };

  return (
    <Stack gap="sm">
      <Group justify="space-between" wrap="wrap" gap="sm">
        <Select
          label="Variation"
          value={selected}
          onChange={(v) => v && onSelect(v)}
          data={labels.map((l) => ({ value: l, label: label(planId, l) }))}
          allowDeselect={false}
          w={260}
        />
        <Group gap="md" wrap="wrap" align="center">
          <Group gap={6} wrap="nowrap">
            <Box w={18} h={2} bg={c.focus} />
            <Text size="xs" c="dimmed">
              {label(planId, selected)}
            </Text>
          </Group>
          {selected !== BASELINE_LABEL && (
            <Group gap={6} wrap="nowrap">
              <Box w={18} h={2} bg={c.base} />
              <Text size="xs" c="dimmed">
                {label(planId, BASELINE_LABEL)}
              </Text>
            </Group>
          )}
          <Group gap={6} wrap="nowrap">
            <Box w={18} h={2} bg={c.other} />
            <Text size="xs" c="dimmed">
              Other variations
            </Text>
          </Group>
          <Button
            size="xs"
            variant="default"
            onClick={() => setTableView((v) => !v)}
            aria-pressed={tableView}
          >
            {tableView ? 'Chart view' : 'Table view'}
          </Button>
        </Group>
      </Group>
      {columns.length === 0 ? (
        <Text size="sm" c="dimmed">
          No days in this range yet.
        </Text>
      ) : tableView ? (
        <ScrollArea type="auto">
          <Table aria-label={`${selected} against ${BASELINE_LABEL} by day`}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Night</Table.Th>
                <Table.Th>{selected} status</Table.Th>
                <Table.Th ta="right">{selected} worst case</Table.Th>
                <Table.Th ta="right">Signals</Table.Th>
                <Table.Th ta="right">{BASELINE_LABEL} worst case</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {columns.map((col, i) => (
                <Table.Tr key={col.key}>
                  <Table.Td>{col.label}</Table.Td>
                  <Table.Td>
                    {focus[i] ? (
                      <StatusChip status={focus[i].status} pendingDay={focus[i].pendingDay} />
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                  <Table.Td ta="right">
                    {focus[i] ? <SolValue value={focus[i].worstCasePnl} strong /> : '—'}
                  </Table.Td>
                  <Table.Td ta="right" ff="monospace">
                    {focus[i]?.signals ?? '—'}
                  </Table.Td>
                  <Table.Td ta="right">
                    {base[i] ? <SolValue value={base[i].worstCasePnl} /> : '—'}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      ) : (
        <>
          <Chart
            ariaLabel={`Worst-case net P&L of ${selected} and ${BASELINE_LABEL} for the 7 days ending each day, from ${x[0]} to ${x[x.length - 1]}. Latest: ${selected} ${focus[focus.length - 1] ? formatDecimal(focus[focus.length - 1]!.worstCasePnl) : 'not scored'} SOL.`}
            height={300}
            option={lineOption}
            onEvents={{ click: openNight }}
          />
          <Text size="xs" fw={600}>
            Signals in each window, {selected}
          </Text>
          <Chart
            ariaLabel={`Signals in each 7-day window for ${selected}; 10 are needed for a result.`}
            height={130}
            option={signalsOption}
            onEvents={{ click: openNight }}
          />
          <Text size="xs" c="dimmed">
            Hollow points: under 10 signals (No result) or pending. Hover a day for both values;
            click to open it.
          </Text>
        </>
      )}
    </Stack>
  );
}
