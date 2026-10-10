import { Badge, Code, ScrollArea, Table, Text } from '@mantine/core';
import type { Observation, UsdtObservation } from '../api/types';
import { humanizeCode, formatSlot, usdtRateNote } from '../lib/format';
import { recordedTradeSignature, transactionLinks } from '../lib/marketLinks';
import { Decimal, Timestamp } from './DisplayValues';
import { MarketLinks } from './MarketLinks';
import { PagingFooter } from './Panels';
import { useClientPaging } from './useClientPaging';
import { EmptyState } from './StateViews';

export function QualityBadge({ quality }: { quality: string }) {
  const color = quality === 'INVALID' ? 'red' : quality === 'MIDPOINT' ? 'violet' : 'blue';
  return (
    <Badge variant="light" color={color} size="sm" radius="sm">
      {humanizeCode(quality)}
    </Badge>
  );
}

function UsdtPrice({ value }: { value: UsdtObservation | undefined }) {
  if (value?.priceUsdt) {
    return (
      <Text span title={usdtRateNote(value.rate, value.minute) ?? undefined}>
        <Decimal value={value.priceUsdt} />
      </Text>
    );
  }
  return (
    <Text span size="sm" c="dimmed">
      — {value?.unavailableReason && <Code fz="xs">{value.unavailableReason}</Code>}
    </Text>
  );
}

function TradeLinks({ observationId }: { observationId: string }) {
  const signature = recordedTradeSignature(observationId);
  return signature ? <MarketLinks links={transactionLinks(signature)} /> : null;
}

/** With [usdt], the price column shows each observation's USDT conversion (or why it has none). */
export function ObservationTable({ observations, usdt }: { observations: Observation[]; usdt?: UsdtObservation[] }) {
  const paging = useClientPaging(observations, 50);
  const usdtById = new Map((usdt ?? []).map((o) => [o.id, o]));
  if (observations.length === 0) return <EmptyState title="No observations" />;
  return (
    <>
      <ScrollArea type="auto">
        <Table striped verticalSpacing={6} miw={900} fz="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Id</Table.Th>
              <Table.Th ta="right">Slot</Table.Th>
              <Table.Th ta="right">Price slot</Table.Th>
              <Table.Th>Observed</Table.Th>
              <Table.Th ta="right">{usdt ? 'Price (USDT)' : 'Price'}</Table.Th>
              <Table.Th ta="right">Liquidity (quote)</Table.Th>
              <Table.Th>Quality</Table.Th>
              <Table.Th>Source</Table.Th>
              <Table.Th>Commitment</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {paging.visible.map((o) => (
              <Table.Tr key={o.id}>
                <Table.Td ff="monospace">{o.id}</Table.Td>
                <Table.Td ta="right">{formatSlot(o.slot)}</Table.Td>
                <Table.Td ta="right">{formatSlot(o.priceSlot)}</Table.Td>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>
                  <Timestamp iso={o.observedTime} />
                </Table.Td>
                <Table.Td ta="right">
                  {usdt ? <UsdtPrice value={usdtById.get(o.id)} /> : <Decimal value={o.price} />}
                </Table.Td>
                <Table.Td ta="right">
                  {o.liquidityQuote == null ? <Text span c="dimmed" size="sm">unknown</Text> : <Decimal value={o.liquidityQuote} />}
                </Table.Td>
                <Table.Td>
                  <QualityBadge quality={o.quality} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{o.source}</Text>
                  <Text size="xs" c="dimmed" ff="monospace">
                    {o.sourceEventId}
                  </Text>
                  <TradeLinks observationId={o.id} />
                </Table.Td>
                <Table.Td>{o.commitment}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <PagingFooter page={paging.page} pages={paging.pages} total={paging.total} onChange={paging.setPage} noun="observations" />
    </>
  );
}
