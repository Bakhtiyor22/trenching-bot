import {
  Box,
  Group,
  ScrollArea,
  SegmentedControl,
  Select,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import type { EChartsOption } from 'echarts';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import type { AutomationEvaluation } from '../../api/automation';
import { useChartTokens } from '../../charts/chartTokens';
import { Chart } from '../../components/Chart';
import { formatSignedDecimal, MINUS, toChartNumber } from '../../lib/decimal';
import { paths } from '../../paths';
import { FONT_MONO } from '../../theme';
import { BASELINE_LABEL, statusGrid, statusText, variationChange } from './automationModel';
import { EvaluationChip, SIGNALS_NEEDED, SolValue } from './AutomationShared';

function label(planId: string, l: string): string {
  const change = variationChange(planId, l);
  return change ? `${l} · ${change}` : l;
}

/** An axis tick with a sign: "+0.1", "0", "−0.1". */
function signedTick(value: number): string {
  return value > 0 ? `+${value}` : value < 0 ? `${MINUS}${Math.abs(value)}` : '0';
}

/**
 * Trend option B: one variation's rolling worst-case P&L against the default (V00), the other variations as faint
 * context, with a labelled zero line. Every day of the range is a category, so a day without rows is a gap in the line
 * (as in the status grid), never squeezed out. Hollow points mark windows under 10 signals (No result) or still pending.
 * The signals sit in a second, separate chart, never on a second y-axis.
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
  const t = useChartTokens();
  const [view, setView] = useState<'chart' | 'table'>('chart');
  const grid = statusGrid(days, evaluations, labels);
  const columns = grid.columns;
  const cells = (l: string) =>
    grid.rows.find((r) => r.label === l)?.cells ?? columns.map(() => null);
  const focus = cells(selected);
  const base = cells(BASELINE_LABEL);
  const x = columns.map((col) => col.short);
  const num = (e: AutomationEvaluation | null) => (e ? toChartNumber(e.worstCasePnl) : null);
  const others = labels.filter((l) => l !== selected && l !== BASELINE_LABEL);
  const scored = (e: AutomationEvaluation | null | undefined) =>
    e != null && e.signals >= SIGNALS_NEEDED && e.status !== 'PENDING';

  const lineOption: EChartsOption = {
    animation: false,
    grid: { left: 64, right: 72, top: 16, bottom: 36 },
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) => {
        const list = params as Array<{ dataIndex: number }>;
        const i = list[0]?.dataIndex ?? 0;
        const line = (name: string, e: AutomationEvaluation | null | undefined) =>
          e
            ? `${name}: ${formatSignedDecimal(e.worstCasePnl)} SOL · ${statusText[e.status]} · ${e.signals} signals, ${e.unscored} unscored`
            : `${name}: no row`;
        return [
          `<b>${columns[i]?.label ?? ''}</b>`,
          line(selected, focus[i]),
          selected === BASELINE_LABEL ? '' : line(BASELINE_LABEL, base[i]),
        ]
          .filter(Boolean)
          .join('<br/>');
      },
    },
    xAxis: { type: 'category', data: x, boundaryGap: false },
    yAxis: {
      type: 'value',
      name: 'Worst-case net P&L, SOL',
      nameLocation: 'middle',
      nameGap: 48,
      axisLabel: { formatter: signedTick },
    },
    series: [
      ...others.map((l) => ({
        name: l,
        type: 'line' as const,
        data: cells(l).map(num),
        symbol: 'none',
        lineStyle: { width: 1, color: t.lineStrong },
        itemStyle: { color: t.lineStrong },
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
              lineStyle: { width: 2, color: t.dimmed },
              itemStyle: { color: t.dimmed },
              endLabel: {
                show: true,
                color: t.dimmed,
                fontFamily: FONT_MONO,
                formatter: BASELINE_LABEL,
              },
              z: 2,
            },
          ]),
      {
        name: selected,
        type: 'line' as const,
        // A day without a row is null: a gap in the line, not a point.
        data: focus.map((e) =>
          e
            ? {
                value: num(e),
                symbol: 'circle',
                symbolSize: 9,
                itemStyle: scored(e)
                  ? { color: t.accent, borderColor: t.panel, borderWidth: 2 }
                  : { color: t.panel, borderColor: t.accent, borderWidth: 2 },
              }
            : null,
        ),
        lineStyle: { width: 2, color: t.accent },
        itemStyle: { color: t.accent },
        endLabel: {
          show: true,
          color: t.text,
          fontFamily: FONT_MONO,
          fontWeight: 600,
          formatter: selected,
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: t.neutral, type: 'solid', width: 1 },
          label: { formatter: '0 SOL', color: t.dimmed, position: 'insideStartTop' },
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
      formatter: (params: unknown) => {
        const i = (params as Array<{ dataIndex: number }>)[0]?.dataIndex ?? 0;
        const e = focus[i];
        return `<b>${columns[i]?.label ?? ''}</b><br/>${selected}: ${e ? `${e.signals} signals` : 'no row'}`;
      },
    },
    xAxis: { type: 'category', data: x },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      {
        name: `${selected} signals`,
        type: 'bar',
        barMaxWidth: 28,
        data: focus.map((e) =>
          e
            ? {
                value: e.signals,
                itemStyle: {
                  color: e.signals >= SIGNALS_NEEDED ? t.info : t.neutral,
                  borderRadius: [4, 4, 0, 0],
                },
              }
            : null,
        ),
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: t.dimmed, type: 'dashed' },
          label: { formatter: `${SIGNALS_NEEDED} needed`, color: t.dimmed },
          data: [{ yAxis: SIGNALS_NEEDED }],
        },
      },
    ],
  };
  const openDay = (params: unknown) => {
    const i = (params as { dataIndex?: number }).dataIndex;
    const col = i == null ? undefined : columns[i];
    if (col) void navigate(paths.automationDay(col.key));
  };
  const latest = [...focus].reverse().find((e) => e != null);

  return (
    <Stack gap="sm">
      <Group justify="space-between" wrap="wrap" gap="sm">
        <Select
          label="Variation"
          value={selected}
          onChange={(v) => v && onSelect(v)}
          data={labels.map((l) => ({ value: l, label: label(planId, l) }))}
          allowDeselect={false}
          w={{ base: '100%', sm: 260 }}
        />
        <Group gap="md" wrap="wrap" align="center">
          <Group gap={6} wrap="nowrap">
            <Box w={18} h={2} bg={t.accent} />
            <Text size="xs" c="dimmed">
              {label(planId, selected)}
            </Text>
          </Group>
          {selected !== BASELINE_LABEL && (
            <Group gap={6} wrap="nowrap">
              <Box w={18} h={2} bg={t.dimmed} />
              <Text size="xs" c="dimmed">
                {label(planId, BASELINE_LABEL)}
              </Text>
            </Group>
          )}
          <Group gap={6} wrap="nowrap">
            <Box w={18} h={1} bg={t.lineStrong} />
            <Text size="xs" c="dimmed">
              Other variations
            </Text>
          </Group>
          <SegmentedControl
            size="xs"
            value={view}
            onChange={(v) => setView(v as 'chart' | 'table')}
            data={[
              { value: 'chart', label: 'Chart' },
              { value: 'table', label: 'Table' },
            ]}
            aria-label="Trend view"
          />
        </Group>
      </Group>
      {columns.length === 0 ? (
        <Text size="sm" c="dimmed">
          No days in this range yet.
        </Text>
      ) : view === 'table' ? (
        <ScrollArea type="auto">
          <Table verticalSpacing="xs" aria-label={`${selected} against ${BASELINE_LABEL} by day`}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Day</Table.Th>
                <Table.Th>{selected} status</Table.Th>
                <Table.Th ta="right">{selected} worst case</Table.Th>
                <Table.Th ta="right">Signals</Table.Th>
                <Table.Th ta="right">{BASELINE_LABEL} worst case</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {columns.map((col, i) => {
                const f = focus[i];
                const b = base[i];
                return (
                  <Table.Tr key={col.key}>
                    <Table.Td>{col.label}</Table.Td>
                    <Table.Td>
                      {f ? <EvaluationChip status={f.status} pendingDay={f.pendingDay} /> : '—'}
                    </Table.Td>
                    <Table.Td ta="right">
                      {f ? <SolValue value={f.worstCasePnl} strong /> : '—'}
                    </Table.Td>
                    <Table.Td ta="right" ff="monospace">
                      {f?.signals ?? '—'}
                    </Table.Td>
                    <Table.Td ta="right">{b ? <SolValue value={b.worstCasePnl} /> : '—'}</Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      ) : (
        <>
          <Chart
            ariaLabel={`Worst-case net P&L of ${selected} and ${BASELINE_LABEL} for the 7 days ending each day, from ${x[0]} to ${x[x.length - 1]}. Latest: ${selected} ${latest ? `${formatSignedDecimal(latest.worstCasePnl)} SOL` : 'not scored'}.`}
            height={300}
            option={lineOption}
            onEvents={{ click: openDay }}
          />
          <Text size="xs" fw={600}>
            Signals in each window, {selected}
          </Text>
          <Chart
            ariaLabel={`Signals in each 7-day window for ${selected}; ${SIGNALS_NEEDED} are needed for a result.`}
            height={130}
            option={signalsOption}
            onEvents={{ click: openDay }}
          />
          <Text size="xs" c="dimmed">
            Hollow points: under {SIGNALS_NEEDED} signals (No result) or pending. A gap is a day
            without a row. Hover a day for the exact values; click to open it.
          </Text>
        </>
      )}
    </Stack>
  );
}
