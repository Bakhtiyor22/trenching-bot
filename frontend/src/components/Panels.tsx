import { Card, Group, Pagination, Stack, Table, Text, Title, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';
import { IconInfo } from './Icons';

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
    <Group justify="space-between" align="flex-start" mb="lg" gap="md">
      <Stack gap={4}>
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

/** A titled card section. */
export function Section({
  title,
  hint,
  actions,
  children,
}: {
  title: ReactNode;
  hint?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card withBorder radius="md" padding="lg">
      <Group justify="space-between" mb="md" gap="sm">
        <Group gap={6}>
          <Title order={4}>{title}</Title>
          {hint && (
            <Tooltip label={hint} multiline w={280} withArrow>
              <span style={{ display: 'inline-flex', color: 'var(--mantine-color-dimmed)' }} tabIndex={0}>
                <IconInfo size={16} aria-label="More information" />
              </span>
            </Tooltip>
          )}
        </Group>
        {actions}
      </Group>
      {children}
    </Card>
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
