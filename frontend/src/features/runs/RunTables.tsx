import { Badge, Code, ScrollArea, Table, Text, Tooltip } from '@mantine/core';
import { useEffect, useState, type ReactNode } from 'react';
import type { Close, Fill, RunEvent, RunOrder, RunPosition } from '../../api/types';
import { Decimal, PairLabel, Pnl, ShortId, Timestamp } from '../../components/DisplayValues';
import { MarketLinks } from '../../components/MarketLinks';
import { PagingFooter } from '../../components/Panels';
import { useClientPaging } from '../../components/useClientPaging';
import { EmptyState } from '../../components/StateViews';
import { StatusChip } from '../../components/StatusChip';
import { formatSlot, humanizeCode, signedBps } from '../../lib/format';
import { levelPercent } from '../../lib/percent';
import { recordedEventSignature, transactionLinks } from '../../lib/marketLinks';
import { closeReasonText, knownReasonText } from '../captures/captureReasons';
import { commitmentLabel, sideLabel, sourceLabel } from './recordLabels';

function Flag({ value, label }: { value: boolean; label: string }) {
  return (
    <Badge size="xs" variant="light" color={value ? 'teal' : 'red'} radius="sm">
      {label} {value ? 'yes' : 'no'}
    </Badge>
  );
}

function Wrap({ children, minWidth }: { children: ReactNode; minWidth: number }) {
  return (
    <ScrollArea type="auto">
      <Table striped verticalSpacing="xs" fz="sm" miw={minWidth}>
        {children}
      </Table>
    </ScrollArea>
  );
}

/** A reason code with its plain text, from [describe] when it knows the code, else the humanized code. */
function ReasonText({
  code,
  describe,
}: {
  code: string | null;
  describe?: (code: string) => string | null;
}) {
  if (!code)
    return (
      <Text span c="dimmed" size="sm">
        —
      </Text>
    );
  return (
    <Text span size="sm">
      {describe?.(code) ?? humanizeCode(code)} <Code fz="xs">{code}</Code>
    </Text>
  );
}

export function EventsTable({ events }: { events: RunEvent[] }) {
  const paging = useClientPaging(events, 50);
  if (events.length === 0) return <EmptyState title="No wallet events" />;
  return (
    <>
      <Wrap minWidth={1200}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th ta="right">Slot</Table.Th>
            <Table.Th>Signature / ix</Table.Th>
            <Table.Th>Outcome</Table.Th>
            <Table.Th>Ignored reason</Table.Th>
            <Table.Th>Pair</Table.Th>
            <Table.Th ta="right">Base delta (raw)</Table.Th>
            <Table.Th ta="right">Quote delta (raw)</Table.Th>
            <Table.Th>Checks</Table.Th>
            <Table.Th>Block time</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {paging.visible.map((e) => (
            <Table.Tr key={e.eventId}>
              <Table.Td ta="right">{formatSlot(e.slot)}</Table.Td>
              <Table.Td>
                <ShortId value={e.signature} />{' '}
                <Text span size="xs" c="dimmed">
                  #{e.instructionIndex}
                </Text>
                <MarketLinks links={transactionLinks(recordedEventSignature(e) ?? '')} />
              </Table.Td>
              <Table.Td>
                {e.outcome === 'ACCEPTED' ? (
                  <StatusChip tone="neutral" title={e.side ?? 'ACCEPTED'}>
                    {e.side ? sideLabel(e.side) : 'Accepted'}
                  </StatusChip>
                ) : (
                  <StatusChip tone="pending" glyph="–">
                    Ignored
                  </StatusChip>
                )}
              </Table.Td>
              <Table.Td>
                <ReasonText code={e.ignoredReason} />
              </Table.Td>
              <Table.Td>
                <PairLabel pair={e.pair} />
              </Table.Td>
              <Table.Td ta="right">
                <Decimal value={e.baseDelta} />
                <Text size="xs" c="dimmed">
                  {e.baseDecimals} decimals
                </Text>
              </Table.Td>
              <Table.Td ta="right">
                <Decimal value={e.quoteDelta} />
                <Text size="xs" c="dimmed">
                  {e.quoteDecimals} decimals
                </Text>
              </Table.Td>
              <Table.Td>
                <Flag value={e.successful} label="success" />{' '}
                <Flag value={e.supportedRoute} label="route" />{' '}
                <Flag value={e.signer} label="signer" />
                <Tooltip label={`${e.commitment} · ${e.source}`} withArrow>
                  <Text size="xs" c="dimmed">
                    {commitmentLabel(e.commitment)} · {sourceLabel(e.source)}
                  </Text>
                </Tooltip>
              </Table.Td>
              <Table.Td style={{ whiteSpace: 'nowrap' }}>
                <Timestamp iso={e.blockTime} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Wrap>
      <PagingFooter
        page={paging.page}
        pages={paging.pages}
        total={paging.total}
        onChange={paging.setPage}
        noun="events"
      />
    </>
  );
}

/** What one trade paid besides the curve price: the program's fees, our own price impact and the transaction cost. */
type ExecutionCosts = Pick<
  Close,
  'fee' | 'feeBps' | 'feePercent' | 'priceImpactBps' | 'priceImpactPercent' | 'transactionCost'
>;

function fillCosts(fill: Fill): ExecutionCosts {
  return {
    fee: fill.fee,
    feeBps: fill.feeBps,
    feePercent: fill.feePercent,
    priceImpactBps: fill.priceImpactBps,
    priceImpactPercent: fill.priceImpactPercent,
    transactionCost: fill.priorityFee,
  };
}

function CostsCell({ costs }: { costs: ExecutionCosts | null }) {
  if (!costs) {
    return (
      <Table.Td ta="right" c="dimmed">
        —
      </Table.Td>
    );
  }
  return (
    <Table.Td ta="right" style={{ whiteSpace: 'nowrap' }}>
      <Decimal value={costs.fee} />{' '}
      <Text span size="xs" c="dimmed">
        fees {levelPercent(costs.feePercent, costs.feeBps)}
      </Text>
      <Text size="xs" c="dimmed">
        impact {signedBps(costs.priceImpactBps, costs.priceImpactPercent)} · tx{' '}
        <Decimal value={costs.transactionCost} size="xs" />
      </Text>
    </Table.Td>
  );
}

function FillCells({ fill }: { fill: Fill | null }) {
  if (!fill) {
    return (
      <>
        {Array.from({ length: 5 }, (_, i) => (
          <Table.Td key={i} ta="right" c="dimmed">
            —
          </Table.Td>
        ))}
      </>
    );
  }
  return (
    <>
      <Table.Td ta="right">{formatSlot(fill.actualSlot)}</Table.Td>
      <Table.Td ta="right">
        <Decimal value={fill.executionPrice} />
      </Table.Td>
      <Table.Td ta="right">
        <Decimal value={fill.quantity} />
      </Table.Td>
      <CostsCell costs={fillCosts(fill)} />
      <Table.Td ta="right">
        <Decimal value={fill.costBasis} />
      </Table.Td>
    </>
  );
}

function useFillTarget<T extends { intentId: string }>(
  items: T[],
  selectedIntentId: string | null | undefined,
  phase: 'entry' | 'exit',
) {
  const paging = useClientPaging(items, 50);
  const selectedIndex = items.findIndex((item) => item.intentId === selectedIntentId);
  const selectionKey = selectedIntentId ? `${selectedIntentId}:${selectedIndex}` : '';
  const [previousSelection, setPreviousSelection] = useState('');
  if (selectionKey !== previousSelection) {
    setPreviousSelection(selectionKey);
    if (selectedIndex >= 0) paging.setPage(Math.floor(selectedIndex / 50) + 1);
  }
  useEffect(() => {
    if (selectedIntentId)
      document
        .getElementById(`fill-${encodeURIComponent(selectedIntentId)}-${phase}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [selectedIntentId, phase, paging.page]);
  return paging;
}

const selectedRecordStyle = {
  background: 'var(--console-raised)',
  outline: '1px solid var(--console-line-strong)',
  outlineOffset: -1,
  scrollMarginTop: 24,
};

export function OrdersTable({
  orders,
  selectedIntentId,
}: {
  orders: RunOrder[];
  selectedIntentId?: string | null;
}) {
  const paging = useFillTarget(orders, selectedIntentId, 'entry');
  if (orders.length === 0) return <EmptyState title="No orders" />;
  return (
    <>
      <Wrap minWidth={1250}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Order</Table.Th>
            <Table.Th ta="right">Dip slot</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Rejection reason</Table.Th>
            <Table.Th ta="right">Fill slot</Table.Th>
            <Table.Th ta="right">Execution price</Table.Th>
            <Table.Th ta="right">Quantity</Table.Th>
            <Table.Th ta="right">Fees · impact · tx cost</Table.Th>
            <Table.Th ta="right">Cost basis</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {paging.visible.map((o) => (
            <Table.Tr
              key={o.orderId}
              id={`fill-${encodeURIComponent(o.intentId)}-entry`}
              data-highlighted={o.intentId === selectedIntentId || undefined}
              aria-selected={o.intentId === selectedIntentId || undefined}
              style={o.intentId === selectedIntentId ? selectedRecordStyle : undefined}
            >
              <Table.Td>
                <ShortId value={o.orderId} />
              </Table.Td>
              <Table.Td ta="right">{formatSlot(o.targetSlot)}</Table.Td>
              <Table.Td>
                {o.status === 'FILLED' ? (
                  <StatusChip tone="success" glyph="✓">
                    Filled
                  </StatusChip>
                ) : (
                  <StatusChip tone="attention" glyph="▲" title={o.status}>
                    {o.status === 'REJECTED' ? 'Rejected' : humanizeCode(o.status)}
                  </StatusChip>
                )}
              </Table.Td>
              <Table.Td>
                <ReasonText code={o.rejectionReason} describe={knownReasonText} />
              </Table.Td>
              <FillCells fill={o.fill} />
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Wrap>
      <PagingFooter
        page={paging.page}
        pages={paging.pages}
        total={paging.total}
        onChange={paging.setPage}
        noun="orders"
      />
    </>
  );
}

export function PositionsTable({
  positions,
  selectedIntentId,
}: {
  positions: RunPosition[];
  selectedIntentId?: string | null;
}) {
  const paging = useFillTarget(positions, selectedIntentId, 'exit');
  if (positions.length === 0)
    return (
      <EmptyState title="No positions" description="Cycles without an entry open no position." />
    );
  return (
    <>
      <Wrap minWidth={1660}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Position</Table.Th>
            <Table.Th>Pair</Table.Th>
            <Table.Th>State</Table.Th>
            <Table.Th ta="right">Entry slot</Table.Th>
            <Table.Th ta="right">Entry price</Table.Th>
            <Table.Th ta="right">Quantity</Table.Th>
            <Table.Th ta="right">Entry costs</Table.Th>
            <Table.Th ta="right">Take profit</Table.Th>
            <Table.Th ta="right">Stop loss</Table.Th>
            <Table.Th>Exit reason</Table.Th>
            <Table.Th ta="right">Exit slot</Table.Th>
            <Table.Th ta="right">Exit price</Table.Th>
            <Table.Th ta="right">Exit costs</Table.Th>
            <Table.Th ta="right">Net proceeds</Table.Th>
            <Table.Th ta="right">Realized P&amp;L</Table.Th>
            <Table.Th>Exit unavailable</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {paging.visible.map((p) => (
            <Table.Tr
              key={p.id}
              id={`fill-${encodeURIComponent(p.intentId)}-exit`}
              data-highlighted={p.intentId === selectedIntentId || undefined}
              aria-selected={p.intentId === selectedIntentId || undefined}
              style={p.intentId === selectedIntentId ? selectedRecordStyle : undefined}
            >
              <Table.Td>
                <ShortId value={p.id} />
              </Table.Td>
              <Table.Td>
                <PairLabel pair={p.pair} />
              </Table.Td>
              <Table.Td>
                <StatusChip tone="neutral" glyph={p.state === 'OPEN' ? '◷' : undefined}>
                  {p.state === 'OPEN' ? 'Open' : p.state === 'CLOSED' ? 'Closed' : humanizeCode(p.state)}
                </StatusChip>
              </Table.Td>
              <Table.Td ta="right">{formatSlot(p.fill.actualSlot)}</Table.Td>
              <Table.Td ta="right">
                <Decimal value={p.fill.executionPrice} />
              </Table.Td>
              <Table.Td ta="right">
                <Decimal value={p.fill.quantity} />
              </Table.Td>
              <CostsCell costs={fillCosts(p.fill)} />
              <Table.Td ta="right">
                <Decimal value={p.takeProfitPrice} />
              </Table.Td>
              <Table.Td ta="right">
                <Decimal value={p.stopLossPrice} />
              </Table.Td>
              <Table.Td>
                <ReasonText code={p.close?.reason ?? null} describe={closeReasonText} />
              </Table.Td>
              <Table.Td ta="right">{p.close ? formatSlot(p.close.slot) : '—'}</Table.Td>
              <Table.Td ta="right">
                {p.close ? <Decimal value={p.close.executionPrice} /> : '—'}
              </Table.Td>
              <CostsCell costs={p.close} />
              <Table.Td ta="right">
                {p.close ? <Decimal value={p.close.netProceeds} /> : '—'}
              </Table.Td>
              <Table.Td ta="right">{p.close ? <Pnl value={p.close.realizedPnl} /> : '—'}</Table.Td>
              <Table.Td>
                <ReasonText code={p.exitUnavailableReason} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Wrap>
      <PagingFooter
        page={paging.page}
        pages={paging.pages}
        total={paging.total}
        onChange={paging.setPage}
        noun="positions"
      />
    </>
  );
}
