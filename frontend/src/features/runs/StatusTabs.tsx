import { Group, ScrollArea, SegmentedControl, Text } from '@mantine/core';
import { RUN_STATUSES, type RunStatus } from '../../api/types';
import { formatCount } from '../../lib/format';
import { parseStatus, STATUS_LABELS, type StatusFilter } from './runList';

/**
 * The status tabs (All, Profit, Loss, Flat, Not bought, Still held), each with its count from the server, shared by the
 * Runs list and the sweep runs table so both read and count the same. Without counts (an older backend) the tabs show
 * no numbers rather than zeros.
 */
export function StatusTabs({
  value,
  counts,
  onChange,
}: {
  value: StatusFilter;
  counts: Record<RunStatus, number> | null | undefined;
  onChange: (value: StatusFilter) => void;
}) {
  const all = counts ? RUN_STATUSES.reduce((sum, s) => sum + counts[s], 0) : undefined;
  const label = (text: string, count: number | undefined) => (
    <Group gap={6} wrap="nowrap" justify="center">
      <span>{text}</span>
      {count !== undefined && ' '}
      {count !== undefined && (
        <Text span size="xs" ff="monospace" c="dimmed">
          {formatCount(count)}
        </Text>
      )}
    </Group>
  );
  return (
    <ScrollArea type="never" maw="100%">
      <SegmentedControl
        aria-label="Status"
        value={value}
        onChange={(next) => onChange(parseStatus(next))}
        data={[
          { value: 'all', label: label('All', all) },
          ...RUN_STATUSES.map((s) => ({ value: s, label: label(STATUS_LABELS[s], counts?.[s]) })),
        ]}
      />
    </ScrollArea>
  );
}
