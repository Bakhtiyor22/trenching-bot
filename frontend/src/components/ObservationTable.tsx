import { ScrollArea, Table, Text, Tooltip } from '@mantine/core';
import { useEffect, useState } from 'react';
import type { Observation, UsdtObservation } from '../api/types';
import { codeLabel } from '../lib/codes';
import { humanizeCode, formatSlot, usdtRateNote } from '../lib/format';
import { commitmentLabel, sideLabel, sourceLabel } from '../features/runs/recordLabels';
import { recordedTradeSignature, transactionLinks } from '../lib/marketLinks';
import { Decimal, ShortId, Timestamp } from './DisplayValues';
import { MarketLinks } from './MarketLinks';
import { PagingFooter } from './Panels';
import { useClientPaging } from './useClientPaging';
import { EmptyState } from './StateViews';
import { StatusChip } from './StatusChip';

/** An observation's quality in words; only an invalid one is marked as a failure. */
export function QualityBadge({ quality }: { quality: string }) {
  return quality === 'INVALID' ? (
    <StatusChip tone="failure" glyph="✕" title={quality}>
      {humanizeCode(quality)}
    </StatusChip>
  ) : (
    <StatusChip tone="neutral" title={quality}>
      {humanizeCode(quality)}
    </StatusChip>
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
    <Text span size="sm" c="dimmed" title={value?.unavailableReason ?? undefined}>
      —{value?.unavailableReason && ` ${codeLabel(value.unavailableReason)}`}
    </Text>
  );
}

function TradeLinks({ observationId }: { observationId: string }) {
  const signature = recordedTradeSignature(observationId);
  return signature ? <MarketLinks links={transactionLinks(signature)} /> : null;
}

/** With [usdt], the price column shows each observation's USDT conversion (or why it has none). */
export function ObservationTable({
  observations,
  usdt,
  selectedId,
}: {
  observations: Observation[];
  usdt?: UsdtObservation[];
  selectedId?: string | null;
}) {
  const paging = useClientPaging(observations, 50);
  const selectedIndex = observations.findIndex((observation) => observation.id === selectedId);
  const selectionKey = selectedId ? `${selectedId}:${selectedIndex}` : '';
  const [previousSelection, setPreviousSelection] = useState('');
  if (selectionKey !== previousSelection) {
    setPreviousSelection(selectionKey);
    if (selectedIndex >= 0) paging.setPage(Math.floor(selectedIndex / 50) + 1);
  }
  useEffect(() => {
    if (selectedId)
      document
        .getElementById(`observation-${encodeURIComponent(selectedId)}`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [selectedId, paging.page]);
  const usdtById = new Map((usdt ?? []).map((o) => [o.id, o]));
  const hasTraders = observations.some((observation) => observation.trade != null);
  if (observations.length === 0) return <EmptyState title="No observations" />;
  return (
    <>
      <ScrollArea type="auto">
        <Table striped verticalSpacing="xs" miw={900} fz="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th className="console-sticky">Id</Table.Th>
              <Table.Th ta="right">Slot</Table.Th>
              <Table.Th ta="right">Price slot</Table.Th>
              <Table.Th>Observed</Table.Th>
              {hasTraders && <Table.Th>Trader</Table.Th>}
              <Table.Th ta="right">{usdt ? 'Price (USDT)' : 'Price'}</Table.Th>
              <Table.Th ta="right">Liquidity (quote)</Table.Th>
              <Table.Th>Quality</Table.Th>
              <Table.Th>Source</Table.Th>
              <Table.Th>Commitment</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {paging.visible.map((o) => (
              <Table.Tr
                key={o.id}
                id={`observation-${encodeURIComponent(o.id)}`}
                data-highlighted={o.id === selectedId || undefined}
                aria-selected={o.id === selectedId || undefined}
                style={
                  o.id === selectedId
                    ? {
                        background: 'var(--console-raised)',
                        outline: '1px solid var(--console-line-strong)',
                        outlineOffset: -1,
                        scrollMarginTop: 24,
                      }
                    : undefined
                }
              >
                <Table.Td className="console-sticky">
                  <ShortId value={o.id} />
                </Table.Td>
                <Table.Td ta="right">{formatSlot(o.slot)}</Table.Td>
                <Table.Td ta="right">{formatSlot(o.priceSlot)}</Table.Td>
                <Table.Td style={{ whiteSpace: 'nowrap' }}>
                  <Timestamp iso={o.observedTime} />
                </Table.Td>
                {hasTraders && (
                  <Table.Td>
                    {o.trade ? (
                      <>
                        <ShortId value={o.trade.trader} />
                        <Text size="xs" c="dimmed">
                          {sideLabel(o.trade.side)}
                        </Text>
                      </>
                    ) : (
                      <Text span c="dimmed">
                        —
                      </Text>
                    )}
                  </Table.Td>
                )}
                <Table.Td ta="right">
                  {usdt ? <UsdtPrice value={usdtById.get(o.id)} /> : <Decimal value={o.price} />}
                </Table.Td>
                <Table.Td ta="right">
                  {o.liquidityQuote == null ? (
                    <Text span c="dimmed" size="sm">
                      unknown
                    </Text>
                  ) : (
                    <Decimal value={o.liquidityQuote} />
                  )}
                </Table.Td>
                <Table.Td>
                  <QualityBadge quality={o.quality} />
                </Table.Td>
                <Table.Td>
                  <Tooltip label={o.source} withArrow>
                    <Text size="sm">{sourceLabel(o.source)}</Text>
                  </Tooltip>
                  <ShortId value={o.sourceEventId} size="xs" c="dimmed" />
                  <TradeLinks observationId={o.id} />
                </Table.Td>
                <Table.Td title={o.commitment}>{commitmentLabel(o.commitment)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </ScrollArea>
      <PagingFooter
        page={paging.page}
        pages={paging.pages}
        total={paging.total}
        onChange={paging.setPage}
        noun="observations"
      />
    </>
  );
}
