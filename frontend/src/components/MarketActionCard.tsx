import { ActionIcon, Anchor, Box, Button, Group, Stack, Text } from '@mantine/core';
import { actionTime, trimDecimal, type MarketAction, type MarkerAnchor } from '../charts/marketMarkers';
import { exactUnits } from '../charts/tradeWallets';
import { shortId } from '../lib/format';
import { transactionLinks } from '../lib/marketLinks';
import { FONT_MONO, terminal } from '../theme';

export interface MarketActionCardProps {
  action: MarketAction;
  /** One-based position among the Decu actions. */
  index: number;
  total: number;
  firstBuy?: boolean;
  positionClosed?: boolean;
  view: 'mcap' | 'price';
  anchor: MarkerAnchor;
  width: number;
  onClose: () => void;
  /** Absent where the page has no record to open; the link is then hidden. */
  onOpenDetails?: () => void;
}

export function MarketActionCard({ action, index, total, firstBuy = false, positionClosed = false, view, anchor, width, onClose, onOpenDetails }: MarketActionCardProps) {
  const paper = action.kind === 'PAPER';
  const sell = action.side === 'SELL';
  const color = paper ? terminal.info : terminal.accent;
  const transaction = action.kind === 'OBSERVED' && action.signature ? transactionLinks(action.signature)[0] : undefined;
  const cardWidth = Math.min(300, Math.max(0, width - 16));
  const left = Math.max(8, Math.min(anchor.x - cardWidth / 2, width - cardWidth - 8));
  const below = anchor.y < 230;
  const note = paper ? action.reason : firstBuy ? 'First buy · started this cycle' : positionClosed ? 'Position closed' : null;
  const heading = paper ? `Bot paper ${action.phase.toLowerCase()}` : `Decu ${sell ? 'sold' : 'bought'}`;

  return (
    <Box role="dialog" aria-label="Trade details" data-market-action-card p="sm" style={{ position: 'absolute', zIndex: 5, width: cardWidth, left, top: below ? anchor.y + 26 : anchor.y - 26, transform: below ? undefined : 'translateY(-100%)', background: terminal.raised, border: `1px solid ${terminal.neutral}`, borderRadius: 10, boxShadow: '0 12px 36px rgba(0,0,0,.4)', color: terminal.text }}>
      <Stack gap="xs">
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <Group gap="xs" wrap="nowrap">
            <Box aria-hidden="true" style={{ width: 26, height: 26, borderRadius: paper ? 0 : '50%', border: `2px solid ${color}`, color: !paper && !sell ? terminal.ground : color, background: !paper && !sell ? color : terminal.panel, display: 'grid', placeItems: 'center', fontWeight: 700 }}>{paper ? '◇' : sell ? 'S' : 'B'}</Box>
            <Box><Text size="sm" fw={600}>{heading}</Text><Text size="xs" c="dimmed">{paper ? 'Simulated fill · not a real trade' : `Decu · ${shortId(action.trader)} · ${index} of ${total}`}</Text></Box>
          </Group>
          <ActionIcon variant="subtle" color="gray" aria-label="Close action details" onClick={onClose}>×</ActionIcon>
        </Group>
        <Text style={{ fontFamily: FONT_MONO, overflowWrap: 'anywhere' }} size="xl" fw={600}>{paper ? `${trimDecimal(action.quantity)} tokens` : `${sell ? '+' : ''}${trimDecimal(exactUnits(action.solLamports, 9))} SOL`}</Text>
        {!paper && <CardRow label="Tokens" value={trimDecimal(exactUnits(action.tokenRaw, 6))} />}
        <CardRow label="Mcap" value={action.mcap === null ? 'Unavailable' : `$${trimDecimal(action.mcap)}`} emphasis={view === 'mcap'} />
        <CardRow label="Price (SOL)" value={trimDecimal(action.price)} emphasis={view === 'price'} />
        <CardRow label="Time" value={`${actionTime(action.time)} · slot ${action.slot}`} />
        {note && <Text size="xs" c={color}>{note}</Text>}
        <Group justify="space-between" mt={4}>
          {transaction ? <Anchor href={transaction.href} target="_blank" rel="noopener noreferrer" size="xs">Transaction ↗</Anchor> : <span />}
          {onOpenDetails && <Button variant="subtle" size="compact-xs" onClick={onOpenDetails}>{paper ? 'Open fill →' : 'Open action →'}</Button>}
        </Group>
      </Stack>
    </Box>
  );
}

function CardRow({ label, value, emphasis = false }: { label: string; value: string; emphasis?: boolean }) {
  return <Group justify="space-between" gap="xs" wrap="nowrap"><Text size="xs" c="dimmed">{label}</Text><Text size="xs" fw={emphasis ? 600 : 400} style={{ fontFamily: FONT_MONO, textAlign: 'right', overflowWrap: 'anywhere' }}>{value}</Text></Group>;
}
