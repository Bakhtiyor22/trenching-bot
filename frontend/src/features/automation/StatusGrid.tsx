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
import type { AutomationEvaluation, AutomationSession } from '../../api/automation';
import { formatDecimal } from '../../lib/decimal';
import { paths } from '../../paths';
import {
  GRID_NIGHT_CHOICES,
  shortSol,
  statusGlyph,
  statusGrid,
  statusText,
  variationChange,
  type GridNights,
} from './automationModel';
import { SolValue, StatusChip } from './AutomationShared';

function cellLabel(
  label: string,
  night: string,
  e: AutomationEvaluation | null,
  open: boolean,
): string {
  if (!e)
    return `${label}, night of ${night}: ${open ? 'night still open, not scored yet' : 'not scored'}`;
  return `${label}, night of ${night}: ${statusText[e.status]}, worst case ${formatDecimal(e.worstCasePnl)} SOL, ${e.signals} signals`;
}

function legendCell(status: 'SUCCESS' | 'NO_SUCCESS' | 'NO_RESULT' | 'NONE', glyph: string) {
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
      {(['SUCCESS', 'NO_SUCCESS', 'NO_RESULT'] as const).map((s) => (
        <Group key={s} gap={6} wrap="nowrap">
          {legendCell(s, statusGlyph[s])}
          <Text size="xs" c="dimmed">
            {statusText[s]}
            {s === 'NO_RESULT' && ' (under 10 signals)'}
          </Text>
        </Group>
      ))}
      <Group gap={6} wrap="nowrap">
        {legendCell('NONE', '·')}
        <Text size="xs" c="dimmed">
          Not scored yet
        </Text>
      </Group>
    </Group>
  );
}

/**
 * Trend option A: one row per variation, one column per night (oldest left), each cell the status of the 7-night
 * window ending that night. A run of ✓ across a row means the variation is consistent, not lucky. Cells link to their
 * night; the table view lists the same values as text.
 */
export function StatusGrid({
  sessions,
  evaluations,
  zone,
  planId,
  labels,
  selectedLabel,
  onSelectLabel,
  nights,
  onNightsChange,
}: {
  sessions: AutomationSession[];
  evaluations: AutomationEvaluation[];
  zone: string;
  planId: string;
  labels: string[];
  selectedLabel: string | null;
  onSelectLabel: (label: string) => void;
  nights: GridNights;
  onNightsChange: (value: GridNights) => void;
}) {
  const [tableView, setTableView] = useState(false);
  const grid = statusGrid(sessions, evaluations, zone, labels);
  const viewport = useRef<HTMLDivElement>(null);
  const newest = grid.columns.at(-1)?.session.id;
  // Newest nights sit on the right; on a narrow screen open the grid there instead of at the oldest night.
  useEffect(() => {
    const element = viewport.current;
    if (element) element.scrollLeft = element.scrollWidth;
  }, [newest, nights, tableView]);
  const controls = (
    <Group gap="sm" wrap="wrap">
      <SegmentedControl
        size="xs"
        value={nights}
        onChange={(v) => onNightsChange(v as GridNights)}
        data={GRID_NIGHT_CHOICES.map((n) => ({ value: n, label: `${n} nights` }))}
        aria-label="Nights shown"
      />
      <Button
        size="xs"
        variant="default"
        onClick={() => setTableView((v) => !v)}
        aria-pressed={tableView}
      >
        {tableView ? 'Grid view' : 'Table view'}
      </Button>
    </Group>
  );
  if (grid.columns.length === 0) {
    return (
      <Stack gap="sm">
        {controls}
        <Text size="sm" c="dimmed">
          No nights in this range yet.
        </Text>
      </Stack>
    );
  }
  if (tableView) {
    return (
      <Stack gap="sm">
        {controls}
        <ScrollArea type="auto" h={420}>
          <Table stickyHeader aria-label="Status by night, as a table">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Night</Table.Th>
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
                    <Table.Tr key={`${column.session.id}-${row.label}`}>
                      <Table.Td>{column.night.label}</Table.Td>
                      <Table.Td ff="monospace">{row.label}</Table.Td>
                      <Table.Td>
                        {e ? (
                          <StatusChip status={e.status} />
                        ) : (
                          <Text size="sm" c="dimmed">
                            Not scored
                          </Text>
                        )}
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
        <table
          style={{ borderCollapse: 'separate', borderSpacing: 2 }}
          aria-label="Status by night"
        >
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
                  key={c.session.id}
                  scope="col"
                  fz={11}
                  fw={500}
                  c="dimmed"
                  ff="monospace"
                  ta="center"
                  style={{ whiteSpace: 'nowrap' }}
                >
                  {c.night.short}
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
                    const open = column.session.state !== 'CLOSED';
                    const aria = cellLabel(row.label, column.night.label, e, open);
                    return (
                      <td key={column.session.id} style={{ padding: 0 }}>
                        <Tooltip
                          label={
                            e ? (
                              <Stack gap={2}>
                                <Text size="xs" fw={600}>
                                  {row.label} · {column.night.label}
                                </Text>
                                <Text size="xs">{statusText[e.status]}</Text>
                                <Text size="xs" ff="monospace">
                                  worst {shortSol(e.worstCasePnl)} · realized{' '}
                                  {shortSol(e.realizedPnl)} SOL
                                </Text>
                                <Text size="xs">
                                  {e.signals} signals · {e.unscored} unscored
                                </Text>
                              </Stack>
                            ) : (
                              aria
                            )
                          }
                        >
                          <Link
                            to={paths.automationNight(column.session.id)}
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
