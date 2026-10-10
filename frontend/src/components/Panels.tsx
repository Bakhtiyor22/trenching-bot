import { Card, Group, Pagination, Stack, Table, Text, Title, type MantineSpacing } from '@mantine/core';
import { useState, type ReactNode } from 'react';

/** Page title with optional description and right-aligned actions. */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <Group justify="space-between" align="flex-end" mb="lg" gap="md">
      <Stack gap={4} style={{ minWidth: 0 }}>
        <Title order={2}>{title}</Title>
        {description && (
          <Text c="dimmed" size="sm">
            {description}
          </Text>
        )}
      </Stack>
      {actions && <Group gap="sm">{actions}</Group>}
    </Group>
  );
}

/** A titled panel; the hint reads as a subtitle so what a chart shows is never hidden behind an icon. */
export function Section({
  title,
  hint,
  actions,
  children,
  padding = 'lg',
}: {
  title: ReactNode;
  hint?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  padding?: MantineSpacing;
}) {
  return (
    <Card padding={padding} component="section">
      <Group justify="space-between" align="flex-start" mb="md" gap="sm">
        <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
          <Title order={3} fz={15}>
            {title}
          </Title>
          {hint && (
            <Text size="xs" c="dimmed">
              {hint}
            </Text>
          )}
        </Stack>
        {actions}
      </Group>
      {children}
    </Card>
  );
}

/** Font size for a headline figure: full size on wide screens, smaller where a cell gets narrow. */
export const KPI_VALUE_SIZE = 'clamp(20px, 2.2vw, 28px)';

/** One cell of a {@link KpiStrip}: an uppercase label, a large mono value and a footnote. */
export function Kpi({ label, children, hint }: { label: ReactNode; children: ReactNode; hint?: ReactNode }) {
  return (
    <Stack gap={6} p="md" className="console-kpi">
      <Text size="xs" c="dimmed" tt="uppercase" fw={500} style={{ letterSpacing: '0.08em' }}>
        {label}
      </Text>
      <div className="console-kpi-value">{children}</div>
      {hint && (
        <Text size="xs" c="dimmed" ff="monospace">
          {hint}
        </Text>
      )}
    </Stack>
  );
}

/** Key figures in one bordered strip with hairline dividers, wrapping on narrow screens. */
export function KpiStrip({ children, label, min = 220 }: { children: ReactNode; label: string; min?: number }) {
  return (
    <Card padding={0} component="section" aria-label={label}>
      <div className="console-kpi-strip" style={{ gridTemplateColumns: `repeat(auto-fit, minmax(${min}px, 1fr))` }}>
        {children}
      </div>
    </Card>
  );
}

/**
 * Flashes its content once when `watch` changes, so a polled value that moved is noticed. The
 * first render does not flash.
 */
export function Flash({ watch, children }: { watch: unknown; children: ReactNode }) {
  const [previous, setPrevious] = useState(watch);
  const [changes, setChanges] = useState(0);
  if (!Object.is(previous, watch)) {
    setPrevious(watch);
    setChanges(changes + 1);
  }
  return (
    <span key={changes} className={changes > 0 ? 'console-flash' : undefined}>
      {children}
    </span>
  );
}

/** Small label/value stat. */
export function Stat({
  label,
  value,
  hint,
}: {
  label: ReactNode;
  value: ReactNode;
  hint?: ReactNode;
}) {
  return (
    <Stack gap={2}>
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <div>{value}</div>
      {hint && (
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      )}
    </Stack>
  );
}

/** Key/value grid used for headers and config. */
export function KeyValueTable({ rows }: { rows: Array<[ReactNode, ReactNode]> }) {
  return (
    <Table withRowBorders={false} verticalSpacing={4} horizontalSpacing="sm">
      <Table.Tbody>
        {rows.map(([key, value], index) => (
          <Table.Tr key={index}>
            <Table.Th w="40%" fw={500} c="dimmed" style={{ whiteSpace: 'nowrap' }}>
              {key}
            </Table.Th>
            <Table.Td>{value}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

export function PagingFooter({
  page,
  pages,
  total,
  onChange,
  noun = 'rows',
}: {
  page: number;
  pages: number;
  total: number;
  onChange: (page: number) => void;
  noun?: string;
}) {
  return (
    <Group justify="space-between" mt="sm">
      <Text size="sm" c="dimmed">
        {total.toLocaleString('en-US')} {noun}
      </Text>
      {pages > 1 && <Pagination value={page} total={pages} onChange={onChange} size="sm" />}
    </Group>
  );
}
