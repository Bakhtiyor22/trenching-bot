import {
  Anchor,
  Card,
  Group,
  Pagination,
  Stack,
  Table,
  Text,
  Title,
  type MantineSpacing,
} from '@mantine/core';
import { useState, type ReactNode } from 'react';
import { Link, NavLink as RouterNavLink } from 'react-router';
import { formatCount } from '../lib/format';

export interface Crumb {
  label: ReactNode;
  to?: string;
}

/**
 * The one page header: optional breadcrumb, the title row (title, a status chip, short meta), a
 * one-line description, actions on the right and optional underline tabs below.
 */
export function PageHeader({
  title,
  description,
  actions,
  breadcrumbs,
  status,
  meta,
  tabs,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  breadcrumbs?: Crumb[];
  status?: ReactNode;
  meta?: ReactNode;
  tabs?: ReactNode;
}) {
  return (
    <Stack gap="sm" mb="lg" component="header">
      {breadcrumbs && breadcrumbs.length > 0 && (
        <Group component="nav" aria-label="Breadcrumb" gap={6} wrap="wrap">
          {breadcrumbs.map((crumb, index) => (
            <Group key={index} gap={6} wrap="nowrap">
              {index > 0 && (
                <Text size="xs" c="dimmed" aria-hidden>
                  /
                </Text>
              )}
              {crumb.to ? (
                <Anchor component={Link} to={crumb.to} size="xs">
                  {crumb.label}
                </Anchor>
              ) : (
                <Text size="xs" c="dimmed">
                  {crumb.label}
                </Text>
              )}
            </Group>
          ))}
        </Group>
      )}
      <Group justify="space-between" align="flex-start" gap="md">
        <Stack gap={4} style={{ minWidth: 0, flex: '1 1 320px' }}>
          <Group gap="sm" wrap="wrap" align="center">
            <Title order={2} style={{ overflowWrap: 'anywhere' }}>
              {title}
            </Title>
            {status}
            {meta && (
              <Text size="xs" c="dimmed">
                {meta}
              </Text>
            )}
          </Group>
          {description && (
            <Text c="dimmed" size="sm" maw={820}>
              {description}
            </Text>
          )}
        </Stack>
        {actions && (
          <Group gap="sm" wrap="wrap">
            {actions}
          </Group>
        )}
      </Group>
      {tabs}
    </Stack>
  );
}

/** Underline tabs that are real links (`aria-current="page"` on the active one); they scroll sideways on a phone. */
export function SubNav({
  label,
  items,
}: {
  label: string;
  items: Array<{ to: string; label: ReactNode; end?: boolean; active?: boolean }>;
}) {
  return (
    <nav className="console-subnav" aria-label={label}>
      {items.map((item) => (
        <RouterNavLink
          key={item.to}
          to={item.to}
          end={item.end}
          aria-current={item.active === undefined ? undefined : item.active ? 'page' : undefined}
        >
          {item.label}
        </RouterNavLink>
      ))}
    </nav>
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

/** One cell of a {@link KpiStrip}: a sentence-case label, a mono figure and one line of context. */
export function Kpi({
  label,
  children,
  hint,
  size = 'md',
  span,
  text = false,
}: {
  label: ReactNode;
  children: ReactNode;
  hint?: ReactNode;
  /** `hero` for the page's one headline figure. */
  size?: 'hero' | 'md' | 'sm';
  /** `full` spans two columns on wide screens and the whole row on a phone. */
  span?: 'full';
  /** A value in words ("Always on", "19 h ago") rather than a figure: set in the sans, not mono. */
  text?: boolean;
}) {
  return (
    <Stack gap={4} p="md" className="console-kpi" data-span={span}>
      <span className="console-label">{label}</span>
      <div className="console-kpi-value" data-size={size} data-text={text || undefined}>
        {children}
      </div>
      {hint && (
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      )}
    </Stack>
  );
}

/** Key figures in one bordered strip with hairline dividers, wrapping on narrow screens. */
export function KpiStrip({
  children,
  label,
  min = 220,
}: {
  children: ReactNode;
  label: string;
  min?: number;
}) {
  return (
    <Card padding={0} component="section" aria-label={label}>
      <div
        className="console-kpi-strip"
        style={{ gridTemplateColumns: `repeat(auto-fit, minmax(${min}px, 1fr))` }}
      >
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
    <Stack gap={2} miw={0}>
      <span className="console-label">{label}</span>
      {/* Long exact decimals wrap inside their own grid column instead of overlapping the next one. */}
      <div style={{ overflowWrap: 'anywhere' }}>{value}</div>
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
        {formatCount(total)} {noun}
      </Text>
      {pages > 1 && <Pagination value={page} total={pages} onChange={onChange} size="sm" />}
    </Group>
  );
}
