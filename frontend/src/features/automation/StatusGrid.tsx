import {
  Box,
  Button,
  Group,
  ScrollArea,
  SegmentedControl,
  Stack,
  Table,
  Text,
  Tooltip,
} from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import type { AutomationEvaluation } from '../../api/automation';
import { formatSignedDecimal } from '../../lib/decimal';
import { paths } from '../../paths';
import {
  GRID_DAY_CHOICES,
  pendingText,
  statusGlyph,
  statusGrid,
  statusText,
  variationChange,
  type GridDays,
} from './automationModel';
import { EvaluationChip, SolValue } from './AutomationShared';

function cellLabel(label: string, day: string, e: AutomationEvaluation | null): string {
  if (!e) return `${label}, ${day}: no row`;
  if (e.status === 'PENDING')
    return `${label}, ${day}: pending, ${pendingText(e.pendingDay)}, ${e.signals} signals so far`;
  return `${label}, ${day}: ${statusText[e.status]}, worst case ${formatSignedDecimal(e.worstCasePnl)} SOL, ${e.signals} signals`;
}

function legendCell(status: 'SUCCESS' | 'NO_SUCCESS' | 'NO_RESULT' | 'PENDING', glyph: string) {
  return (
    <span
      className="automation-cell"
      data-status={status}
      style={{ width: 18, height: 18, fontSize: 11 }}
      aria-hidden="true"
    >
      {glyph}
    </span>
  );
}

export function StatusGridLegend() {
  return (
    <Group gap="md" wrap="wrap">
      {(['SUCCESS', 'NO_SUCCESS', 'NO_RESULT', 'PENDING'] as const).map((s) => (
        <Group key={s} gap={6} wrap="nowrap">
          {legendCell(s, statusGlyph[s])}
          <Text size="xs" c="dimmed">
            {statusText[s]}
            {s === 'NO_RESULT' && ' (under 10 signals)'}
            {s === 'PENDING' && ' (a day of the window is incomplete)'}
          </Text>
        </Group>
      ))}
    </Group>
  );
}

/**
 * Trend option A: one row per variation, one column per trading day (oldest left), each cell the status of the 7-day
 * window ending that day. A run of ✓ across a row means the variation is consistent, not lucky. Days without buys are
 * real columns, since they count toward the window. Cells link to their day; the table view lists the same values.
 */
export function StatusGrid({
  days,
  evaluations,
  planId,
  labels,
  selectedLabel,
  onSelectLabel,
  span,
  onSpanChange,
}: {
  /** The trading days shown, any order. */
  days: string[];
  evaluations: AutomationEvaluation[];
  planId: string;
  labels: string[];
  selectedLabel: string | null;
  onSelectLabel: (label: string) => void;
  span: GridDays;
  onSpanChange: (value: GridDays) => void;
}) {
  const [tableView, setTableView] = useState(false);
  const grid = statusGrid(days, evaluations, labels);
  const viewport = useRef<HTMLDivElement>(null);
  const newest = grid.columns.at(-1)?.key;
  // Newest days sit on the right; on a narrow screen open the grid there instead of at the oldest day.
  useEffect(() => {
    const element = viewport.current;
    if (element) element.scrollLeft = element.scrollWidth;
  }, [newest, span, tableView]);
  const controls = (
    <Group gap="sm" wrap="wrap">
      <SegmentedControl
        size="xs"
        value={span}
        onChange={(v) => onSpanChange(v as GridDays)}
        data={GRID_DAY_CHOICES.map((n) => ({ value: n, label: `${n} days` }))}
        aria-label="Days shown"
      />
      <SegmentedControl
        size="xs"
        value={tableView ? 'table' : 'grid'}
        onChange={(v) => setTableView(v === 'table')}
        data={[
          { value: 'grid', label: 'Grid' },
          { value: 'table', label: 'Table' },
        ]}
        aria-label="Status by day view"
      />
    </Group>
  );
  if (grid.columns.length === 0 || grid.rows.length === 0) {
    return (
      <Stack gap="sm">
        {controls}
        <Text size="sm" c="dimmed">
          No days in this range yet.
        </Text>
      </Stack>
    );
  }
  if (tableView) {
    return (
      <Stack gap="sm">
        {controls}
        <ScrollArea type="auto" h={420}>
          <Table stickyHeader verticalSpacing="xs" aria-label="Status by day, as a table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Day</Table.Th>
                <Table.Th>Variation</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th ta="right">Worst case</Table.Th>
                <Table.Th ta="right">Realized</Table.Th>
                <Table.Th ta="right">Signals</Table.Th>
                <Table.Th ta="right">Unscored</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {[...grid.columns].reverse().flatMap((column) =>
                grid.rows.map((row) => {
                  const e = row.cells[grid.columns.indexOf(column)];
                  return (
                    <Table.Tr key={`${column.key}-${row.label}`}>
                      <Table.Td>{column.label}</Table.Td>
                      <Table.Td ff="monospace">{row.label}</Table.Td>
                      <Table.Td>
                        {e ? <EvaluationChip status={e.status} pendingDay={e.pendingDay} /> : '—'}
                      </Table.Td>
                      <Table.Td ta="right">
                        {e ? <SolValue value={e.worstCasePnl} strong /> : '—'}
                      </Table.Td>
                      <Table.Td ta="right">{e ? <SolValue value={e.realizedPnl} /> : '—'}</Table.Td>
                      <Table.Td ta="right" ff="monospace">
                        {e?.signals ?? '—'}
                      </Table.Td>
                      <Table.Td ta="right" ff="monospace">
                        {e?.unscored ?? '—'}
                      </Table.Td>
                    </Table.Tr>
                  );
                }),
              )}
            </Table.Tbody>
          </Table>
        </ScrollArea>
      </Stack>
    );
  }
  return (
    <Stack gap="sm">
      {controls}
      <ScrollArea type="auto" offsetScrollbars viewportRef={viewport}>
        <table style={{ borderCollapse: 'separate', borderSpacing: 2 }} aria-label="Status by day">
          <thead>
            <tr>
              <Box
                component="th"
                className="automation-sticky"
                ta="left"
                pr="sm"
                fz="xs"
                c="dimmed"
                fw={500}
                scope="col"
              >
                Variation
              </Box>
              {grid.columns.map((c) => (
                <Box
                  component="th"
                  key={c.key}
                  scope="col"
                  fz={11}
                  fw={500}
                  c="dimmed"
                  ff="monospace"
                  ta="center"
                  style={{ whiteSpace: 'nowrap' }}
                >
                  {c.short}
                </Box>
              ))}
            </tr>
          </thead>
          <tbody>
            {grid.rows.map((row) => {
              const change = variationChange(planId, row.label);
              const selected = row.label === selectedLabel;
              return (
                <tr key={row.label}>
                  <Box
                    component="th"
                    scope="row"
                    className="automation-sticky"
                    ta="left"
                    pr="sm"
                    fw={400}
                    style={{ whiteSpace: 'nowrap' }}
                  >
                    <Button
                      variant={selected ? 'light' : 'subtle'}
                      color={selected ? undefined : 'gray'}
                      size="compact-sm"
                      onClick={() => onSelectLabel(row.label)}
                      aria-pressed={selected}
                      aria-label={`Show ${row.label} over time`}
                      styles={{ label: { fontWeight: 400 } }}
                    >
                      <Text span ff="monospace" fw={600} size="sm">
                        {row.label}
                      </Text>
                      {change && (
                        <Text span size="xs" c="dimmed" ml={6} visibleFrom="sm">
                          {change}
                        </Text>
                      )}
                    </Button>
                  </Box>
                  {row.cells.map((e, i) => {
                    const column = grid.columns[i]!;
                    const aria = cellLabel(row.label, column.label, e);
                    return (
                      <td key={column.key} style={{ padding: 0 }}>
                        <Tooltip
                          label={
                            e ? (
                              <Stack gap={2}>
                                <Text size="xs" fw={600}>
                                  {row.label} · {column.label}
                                </Text>
                                <Text size="xs">
                                  {e.status === 'PENDING'
                                    ? `Pending, ${pendingText(e.pendingDay)}`
                                    : statusText[e.status]}
                                </Text>
                                <Text size="xs" ff="monospace">
                                  worst case {formatSignedDecimal(e.worstCasePnl)} SOL
                                </Text>
                                <Text size="xs" ff="monospace">
                                  realized {formatSignedDecimal(e.realizedPnl)} SOL
                                </Text>
                                <Text size="xs">
                                  {e.signals} signals · {e.unscored} unscored
                                  {e.status === 'PENDING' && ' so far'}
                                </Text>
                              </Stack>
                            ) : (
                              aria
                            )
                          }
                        >
                          <Link
                            to={paths.automationDay(column.key)}
                            className="automation-cell"
                            data-status={e?.status ?? 'NONE'}
                            data-selected={selected}
                            aria-label={aria}
                          >
                            <span aria-hidden="true">{e ? statusGlyph[e.status] : '·'}</span>
                          </Link>
                        </Tooltip>
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </ScrollArea>
    </Stack>
  );
}
