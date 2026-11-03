import { Group, Text } from '@mantine/core';
import type { ReactNode } from 'react';

export interface MeterSegment {
  value: number;
  /** A CSS colour, normally a `--console-*` token. */
  color: string;
  label: string;
}

/** Share of `value` in `total` as a CSS width, clamped to 0–100 %. */
function meterWidth(value: number, total: number): string {
  if (!(total > 0) || !(value > 0)) return '0%';
  return `${Math.min(100, (value / total) * 100)}%`;
}

/**
 * The console's one bar: a single value against a maximum (requests against a cap, signals against
 * the 10 needed), or segments of a whole (filled and rejected, buy states) separated by a 2 px gap.
 * Counts only, never amounts. The figures themselves are always shown as text next to it.
 */
export function Meter({
  label,
  value,
  max,
  segments,
  color = 'var(--console-info)',
  size = 'md',
}: {
  /** Accessible name, e.g. "Requests used of the 24 hour cap". */
  label: string;
  value?: number;
  max?: number;
  segments?: MeterSegment[];
  color?: string;
  size?: 'md' | 'lg';
}) {
  if (segments) {
    const total = segments.reduce((sum, s) => sum + Math.max(0, s.value), 0);
    return (
      <div
        className="console-meter"
        data-size={size}
        role="img"
        aria-label={`${label}: ${segments.map((s) => `${s.value} ${s.label}`).join(', ')}`}
      >
        {segments
          .filter((s) => s.value > 0)
          .map((s) => (
            <span key={s.label} style={{ width: meterWidth(s.value, total), background: s.color }} />
          ))}
      </div>
    );
  }
  const now = Math.max(0, value ?? 0);
  return (
    <div
      className="console-meter"
      data-size={size}
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max ?? 0}
      aria-valuenow={now}
    >
      {now > 0 && <span style={{ width: meterWidth(now, max ?? 0), background: color, borderRadius: 3 }} />}
    </div>
  );
}

/** A small legend for a segmented meter: a swatch and the word beside each count. */
export function MeterLegend({ segments }: { segments: Array<MeterSegment & { text?: ReactNode }> }) {
  return (
    <Group gap="md" wrap="wrap">
      {segments.map((s) => (
        <Group key={s.label} gap={6} wrap="nowrap">
          <span aria-hidden style={{ width: 8, height: 8, borderRadius: 2, background: s.color, flex: 'none' }} />
          <Text size="xs" c="dimmed">
            {s.text ?? `${s.value} ${s.label}`}
          </Text>
        </Group>
      ))}
    </Group>
  );
}
