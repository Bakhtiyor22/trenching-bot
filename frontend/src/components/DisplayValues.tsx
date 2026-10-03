import { ActionIcon, Badge, CopyButton, Group, Text, Tooltip, type TextProps } from '@mantine/core';
import type { Pair } from '../api/types';
import { formatDecimal, pnlColor } from '../lib/decimal';
import { formatLocalTime, mintLabel, shortId } from '../lib/format';
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

/** Exact P&L string, colored by sign. */
export function Pnl({ value, ...textProps }: { value: string } & TextProps) {
  return (
    <Text span ff="monospace" size="sm" fw={600} c={pnlColor(value)} {...textProps}>
      {formatDecimal(value)}
    </Text>
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
