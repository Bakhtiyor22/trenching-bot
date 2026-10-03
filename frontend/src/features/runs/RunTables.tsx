import { Badge, Code, ScrollArea, Table, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import type { Fill, RunEvent, RunOrder, RunPosition } from '../../api/types';
import { Decimal, PairLabel, Pnl, ShortId, Timestamp } from '../../components/DisplayValues';
import { PagingFooter } from '../../components/Panels';
import { useClientPaging } from '../../components/useClientPaging';
import { EmptyState } from '../../components/StateViews';
import { formatSlot, humanizeCode, offsetLabel } from '../../lib/format';

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
      <Table striped verticalSpacing={6} fz="sm" miw={minWidth}>
        {children}
      </Table>
    </ScrollArea>
  );
}

function ReasonText({ code }: { code: string | null }) {
  if (!code) return <Text span c="dimmed" size="sm">—</Text>;
  return (
    <Text span size="sm">
      {humanizeCode(code)} <Code fz="xs">{code}</Code>
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
                <ShortId value={e.signature} /> <Text span size="xs" c="dimmed">#{e.instructionIndex}</Text>
              </Table.Td>
              <Table.Td>
                {e.outcome === 'ACCEPTED' ? (
                  <Badge color={e.side === 'SELL' ? 'grape' : 'teal'} variant="light">
                    {e.side ?? 'ACCEPTED'}
                  </Badge>
                ) : (
                  <Badge color="gray" variant="light">
                    Ignored
                  </Badge>
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
                <Text size="xs" c="dimmed">{e.baseDecimals} decimals</Text>
              </Table.Td>
              <Table.Td ta="right">
                <Decimal value={e.quoteDelta} />
                <Text size="xs" c="dimmed">{e.quoteDecimals} decimals</Text>
              </Table.Td>
              <Table.Td>
                <Flag value={e.successful} label="success" /> <Flag value={e.supportedRoute} label="route" />{' '}
                <Flag value={e.signer} label="signer" />
                <Text size="xs" c="dimmed">
                  {e.commitment} · {e.source}
                </Text>
              </Table.Td>
              <Table.Td style={{ whiteSpace: 'nowrap' }}>
                <Timestamp iso={e.blockTime} />
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Wrap>
      <PagingFooter page={paging.page} pages={paging.pages} total={paging.total} onChange={paging.setPage} noun="events" />
    </>
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
      <Table.Td ta="right"><Decimal value={fill.executionPrice} /></Table.Td>
      <Table.Td ta="right"><Decimal value={fill.quantity} /></Table.Td>
      <Table.Td ta="right"><Decimal value={fill.fee} /></Table.Td>
      <Table.Td ta="right"><Decimal value={fill.costBasis} /></Table.Td>
    </>
  );
}

export function OrdersTable({ orders }: { orders: RunOrder[] }) {
  const paging = useClientPaging(orders, 50);
  if (orders.length === 0) return <EmptyState title="No orders" />;
  return (
    <>
      <Wrap minWidth={1150}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Order</Table.Th>
            <Table.Th>Offset</Table.Th>
            <Table.Th ta="right">Target slot</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Rejection reason</Table.Th>
            <Table.Th ta="right">Fill slot</Table.Th>
            <Table.Th ta="right">Execution price</Table.Th>
            <Table.Th ta="right">Quantity</Table.Th>
            <Table.Th ta="right">Fee</Table.Th>
            <Table.Th ta="right">Cost basis</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {paging.visible.map((o) => (
            <Table.Tr key={o.orderId}>
              <Table.Td><ShortId value={o.orderId} /></Table.Td>
              <Table.Td>{offsetLabel(o.entryOffset)}</Table.Td>
              <Table.Td ta="right">{formatSlot(o.targetSlot)}</Table.Td>
              <Table.Td>
                <Badge color={o.status === 'FILLED' ? 'teal' : 'red'} variant="light">
                  {o.status}
                </Badge>
              </Table.Td>
              <Table.Td><ReasonText code={o.rejectionReason} /></Table.Td>
              <FillCells fill={o.fill} />
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Wrap>
      <PagingFooter page={paging.page} pages={paging.pages} total={paging.total} onChange={paging.setPage} noun="orders" />
    </>
  );
}

export function PositionsTable({ positions }: { positions: RunPosition[] }) {
  const paging = useClientPaging(positions, 50);
  if (positions.length === 0) return <EmptyState title="No positions" description="Rejected scenarios open no position." />;
  return (
    <>
      <Wrap minWidth={1200}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Position</Table.Th>
            <Table.Th>Offset</Table.Th>
            <Table.Th>Pair</Table.Th>
            <Table.Th>State</Table.Th>
            <Table.Th ta="right">Entry slot</Table.Th>
            <Table.Th ta="right">Entry price</Table.Th>
            <Table.Th ta="right">Quantity</Table.Th>
            <Table.Th ta="right">Exit slot</Table.Th>
            <Table.Th ta="right">Exit price</Table.Th>
            <Table.Th ta="right">Net proceeds</Table.Th>
            <Table.Th ta="right">Realized P&amp;L</Table.Th>
            <Table.Th>Exit unavailable</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {paging.visible.map((p) => (
            <Table.Tr key={p.id}>
              <Table.Td><ShortId value={p.id} /></Table.Td>
              <Table.Td>{offsetLabel(p.entryOffset)}</Table.Td>
              <Table.Td><PairLabel pair={p.pair} /></Table.Td>
              <Table.Td>
                <Badge color={p.state === 'OPEN' ? 'yellow' : 'gray'} variant="light">
                  {p.state}
                </Badge>
              </Table.Td>
              <Table.Td ta="right">{formatSlot(p.fill.actualSlot)}</Table.Td>
              <Table.Td ta="right"><Decimal value={p.fill.executionPrice} /></Table.Td>
              <Table.Td ta="right"><Decimal value={p.fill.quantity} /></Table.Td>
              <Table.Td ta="right">{p.close ? formatSlot(p.close.slot) : '—'}</Table.Td>
              <Table.Td ta="right">{p.close ? <Decimal value={p.close.executionPrice} /> : '—'}</Table.Td>
              <Table.Td ta="right">{p.close ? <Decimal value={p.close.netProceeds} /> : '—'}</Table.Td>
              <Table.Td ta="right">{p.close ? <Pnl value={p.close.realizedPnl} /> : '—'}</Table.Td>
              <Table.Td><ReasonText code={p.exitUnavailableReason} /></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Wrap>
      <PagingFooter page={paging.page} pages={paging.pages} total={paging.total} onChange={paging.setPage} noun="positions" />
    </>
  );
}
