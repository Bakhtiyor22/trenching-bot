import { ActionIcon, Badge, CopyButton, Group, Text, Tooltip, type TextProps } from '@mantine/core';
import type { Pair } from '../api/types';
import { useEffect, useState } from 'react';
import { formatDecimal, formatSignedDecimal, pnlColor } from '../lib/decimal';
import { formatLocalTime, formatRelative, mintLabel, shortId } from '../lib/format';
import { IconCheck, IconCopy } from './Icons';

/** Copy-to-clipboard button for a full id/address. */
export function CopyValue({ value, label = 'Copy' }: { value: string; label?: string }) {
  return (
    <CopyButton value={value} timeout={1500}>
      {({ copied, copy }) => (
        <Tooltip label={copied ? 'Copied' : label} withArrow>
          <ActionIcon
            variant="subtle"
            color={copied ? 'teal' : 'gray'}
            size="sm"
            onClick={(event) => {
              event.stopPropagation();
              copy();
            }}
            aria-label={`${label}: ${value}`}
          >
            {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
          </ActionIcon>
        </Tooltip>
      )}
    </CopyButton>
  );
}

/** A shortened id (first 4…last 4) with the full value in a tooltip and a copy button. */
export function ShortId({
  value,
  copy = true,
  label,
  ...textProps
}: { value: string; copy?: boolean; label?: string } & TextProps) {
  return (
    <Group gap={4} wrap="nowrap" component="span" style={{ display: 'inline-flex' }}>
      <Tooltip label={value} withArrow>
        <Text span ff="monospace" size="sm" {...textProps}>
          {label ?? shortId(value)}
        </Text>
      </Tooltip>
      {copy && <CopyValue value={value} label="Copy full value" />}
    </Group>
  );
}

/** A mint address: "SOL" for wrapped SOL, otherwise shortened; copyable. */
export function Mint({ mint, copy = true }: { mint: string; copy?: boolean }) {
  return <ShortId value={mint} label={mintLabel(mint)} copy={copy} />;
}

/** An exact decimal string, digits untouched (thousands separators only). */
export function Decimal({ value, ...textProps }: { value: string | null | undefined } & TextProps) {
  return (
    <Text span ff="monospace" size="sm" {...textProps}>
      {formatDecimal(value)}
    </Text>
  );
}

/**
 * Exact P&L, signed ("+" gain, "−" loss) and coloured by sign, so the direction reads without colour.
 * `trim` drops trailing zeros for a headline and keeps the stored string in a tooltip.
 */
export function Pnl({ value, trim = false, unit, ...textProps }: { value: string; trim?: boolean; unit?: string } & TextProps) {
  const text = formatSignedDecimal(value, { trim });
  const shown = (
    <Text span ff="monospace" size="sm" fw={500} c={pnlColor(value)} style={{ whiteSpace: 'nowrap' }} {...textProps}>
      {text}
      {unit && <span className="console-unit">{unit}</span>}
    </Text>
  );
  if (!trim || text === formatSignedDecimal(value)) return shown;
  return (
    <Tooltip label={`${formatSignedDecimal(value)}${unit ? ` ${unit}` : ''} (exact)`} withArrow events={{ hover: true, focus: true, touch: true }}>
      {shown}
    </Tooltip>
  );
}

/** Local time with the original UTC ISO timestamp in a tooltip. */
export function Timestamp({ iso, ...textProps }: { iso: string | null | undefined } & TextProps) {
  if (!iso) {
    return (
      <Text span size="sm" c="dimmed" {...textProps}>
        —
      </Text>
    );
  }
  return (
    <Tooltip label={`${iso} (UTC)`} withArrow>
      <Text span size="sm" {...textProps}>
        <time dateTime={iso}>{formatLocalTime(iso)}</time>
      </Text>
    </Tooltip>
  );
}

/** "3 min ago", with the local time on hover; text props (size, weight) pass through. */
export function RelativeTime({ iso, ...textProps }: { iso: string | null | undefined } & TextProps) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  if (!iso)
    return (
      <Text span size="sm" c="dimmed" {...textProps}>
        —
      </Text>
    );
  return (
    <Text component="time" dateTime={iso} title={formatLocalTime(iso)} size="sm" {...textProps}>
      {formatRelative(iso, now)}
    </Text>
  );
}

/** A trading pair as "BASE / QUOTE" (shortened mints, wrapped SOL as "SOL"). */
export function PairLabel({ pair }: { pair: Pair }) {
  return (
    <Tooltip label={`${pair.base} / ${pair.quote}`} withArrow>
      <Badge variant="light" color="gray" radius="sm" tt="none" ff="monospace">
        {mintLabel(pair.base)} / {mintLabel(pair.quote)}
      </Badge>
    </Tooltip>
  );
}
