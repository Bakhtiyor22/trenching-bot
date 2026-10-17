import { Alert, Badge, Card, Code, Divider, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import type { Scenario, UsdtScenario } from '../../api/types';
import { Decimal, Pnl } from '../../components/DisplayValues';
import { MarketLinks } from '../../components/MarketLinks';
import { Stat } from '../../components/Panels';
import { formatDecimal } from '../../lib/decimal';
import { levelPercent } from '../../lib/percent';
import { formatSlot, humanizeCode, shortId, signedBps, usdtRateNote } from '../../lib/format';
import { recordedTradeSignature, transactionLinks } from '../../lib/marketLinks';
import { scenarioOutcome } from './scenarioOutcome';

function Reason({ code }: { code: string | null }) {
  return (
    <>
      {humanizeCode(code)} {code && <Code fz="xs">{code}</Code>}
    </>
  );
}

/** A converted USDT value, or a dash with the reason its rate is unavailable. */
function Usdt({
  value,
  reason,
  pnl = false,
}: {
  value: string | null | undefined;
  reason: string | null | undefined;
  pnl?: boolean;
}) {
  if (value == null) {
    return (
      <Text span size="sm" c="dimmed">
        — {reason && <Code fz="xs">{reason}</Code>}
      </Text>
    );
  }
  return pnl ? <Pnl value={value} size="md" /> : <Decimal value={value} />;
}

function Bps({ value, percent }: { value: number; percent?: string }) {
  return (
    <Text span ff="monospace" size="sm">
      {signedBps(value, percent)}
    </Text>
  );
}

function TradeLinks({ label, observationId }: { label: string; observationId: string }) {
  const signature = recordedTradeSignature(observationId);
  if (!signature) return null;
  return (
    <Group gap={6}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <MarketLinks links={transactionLinks(signature)} />
    </Group>
  );
}

function RateNote({
  rate,
  minute,
}: {
  rate: string | null | undefined;
  minute: string | undefined;
}) {
  const note = usdtRateNote(rate, minute);
  return note ? (
    <Text size="xs" c="dimmed">
      {note}
    </Text>
  ) : null;
}

/** The cycle's end state in one badge: take profit, stop loss, still open, or no entry. */
function OutcomeBadge({ scenario }: { scenario: Scenario }) {
  const outcome = scenarioOutcome(scenario);
  return (
    <Badge color={outcome.color} variant="light">
      {outcome.label}
    </Badge>
  );
}

/**
 * One dip-entry cycle: the tracked wallet's first buy of a token (P0), the dip that triggered the entry, the fill, the
 * take-profit and stop-loss levels and the exit. With [usdt], entry and exit amounts show the backend's USDT
 * conversion; P0 and the levels stay in the quote unit, since the strategy compares them there.
 */
export function ScenarioCard({
  scenario,
  usdt,
  unit,
  selected = false,
}: {
  scenario: Scenario;
  usdt?: UsdtScenario;
  unit: string;
  selected?: boolean;
}) {
  const { fill, position, trigger } = scenario;
  const close = position?.close ?? null;
  const filled = scenario.status === 'FILLED' && fill;
  const usdtFill = usdt?.fill;
  const usdtClose = usdt?.close;
  const amount = (
    quote: string,
    converted: string | null | undefined,
    reason: string | null | undefined,
  ): ReactNode => (usdt ? <Usdt value={converted} reason={reason} /> : <Decimal value={quote} />);
  const token = shortId(scenario.pair.base);

  return (
    <Card
      padding="md"
      component="article"
      aria-label={`Cycle ${token}`}
      aria-current={selected || undefined}
      style={selected ? { borderColor: 'var(--mantine-primary-color-filled)' } : undefined}
    >
      <Group justify="space-between" mb="xs" wrap="nowrap">
        <Stack gap={0}>
          <Text fw={600} size="sm">
            Token{' '}
            <Text span ff="monospace" inherit>
              {token}
            </Text>
          </Text>
          <Text size="xs" c="dimmed">
            First buy at slot {formatSlot(scenario.sourceSlot)} · watched through slot{' '}
            {formatSlot(scenario.horizonEndSlot)}
          </Text>
        </Stack>
        <OutcomeBadge scenario={scenario} />
      </Group>

      <Stack gap="sm">
        <Divider label="Waiting for the dip" labelPosition="left" />
        <SimpleGrid cols={2} spacing="xs" verticalSpacing="xs">
          <Stat
            label={`First buy price, P0 (${unit})`}
            value={<Decimal value={scenario.referencePrice} />}
          />
          <Stat label={`Dip level (${unit})`} value={<Decimal value={scenario.dipPrice} />} />
          {trigger && (
            <>
              <Stat label="Dip at slot" value={<Text size="sm">{formatSlot(trigger.slot)}</Text>} />
              <Stat label={`Dip trade price (${unit})`} value={<Decimal value={trigger.price} />} />
              <Stat
                label="Market cap at the dip"
                value={<Text size="sm">${formatDecimal(trigger.marketCapUsd)}</Text>}
              />
              <Stat label="SOL/USDT used" value={<Decimal value={trigger.rate} />} />
            </>
          )}
        </SimpleGrid>
        {trigger && <TradeLinks label="Dip trade" observationId={trigger.observationId} />}

        {!filled ? (
          <Alert color="gray" variant="light" p="xs" title="No entry">
            <Text size="sm">
              <Reason code={scenario.rejectionReason} />
            </Text>
          </Alert>
        ) : (
          <>
            <Divider label="Entry" labelPosition="left" />
            <SimpleGrid cols={2} spacing="xs" verticalSpacing="xs">
              <Stat
                label="Filled at slot"
                value={<Text size="sm">{formatSlot(fill.actualSlot)}</Text>}
              />
              <Stat
                label="Execution price (Pe)"
                value={amount(
                  fill.executionPrice,
                  usdtFill?.executionPriceUsdt,
                  usdtFill?.unavailableReason,
                )}
              />
              <Stat label="Quantity" value={<Decimal value={fill.quantity} />} />
              <Stat
                label={`Fees (${levelPercent(fill.feePercent, fill.feeBps)})`}
                value={amount(fill.fee, usdtFill?.feeUsdt, usdtFill?.unavailableReason)}
              />
              <Stat
                label="Price impact"
                value={<Bps value={fill.priceImpactBps} percent={fill.priceImpactPercent} />}
              />
              <Stat
                label="Transaction cost"
                value={amount(
                  fill.priorityFee,
                  usdtFill?.priorityFeeUsdt,
                  usdtFill?.unavailableReason,
                )}
              />
              <Stat
                label="Cost basis"
                value={amount(fill.costBasis, usdtFill?.costBasisUsdt, usdtFill?.unavailableReason)}
              />
              <Stat
                label={usdt ? `Notional (${unit})` : 'Notional'}
                value={<Decimal value={scenario.notional} />}
              />
            </SimpleGrid>
            {usdt && <RateNote rate={usdtFill?.rate} minute={usdtFill?.minute} />}
            <TradeLinks label="Entry trade" observationId={fill.observationId} />

            <Divider
              label={
                <Group gap={6}>
                  Exit
                  {position && (
                    <Badge
                      size="xs"
                      variant="light"
                      color={position.state === 'OPEN' ? 'yellow' : 'gray'}
                    >
                      {position.state}
                    </Badge>
                  )}
                </Group>
              }
              labelPosition="left"
            />
            {position && (
              <SimpleGrid cols={2} spacing="xs" verticalSpacing="xs">
                <Stat
                  label={`Take profit at (${unit})`}
                  value={<Decimal value={position.takeProfitPrice} />}
                />
                <Stat
                  label={`Stop loss at (${unit})`}
                  value={<Decimal value={position.stopLossPrice} />}
                />
                {position.exitTrigger && (
                  <Stat
                    label="Exit triggered"
                    value={
                      <Text size="sm">
                        {humanizeCode(position.exitTrigger.reason)} at slot{' '}
                        {formatSlot(position.exitTrigger.slot)}
                      </Text>
                    }
                  />
                )}
              </SimpleGrid>
            )}
            {position?.exitTrigger && (
              <TradeLinks
                label="Exit trigger trade"
                observationId={position.exitTrigger.observationId}
              />
            )}
            {close ? (
              <>
                <SimpleGrid cols={2} spacing="xs" verticalSpacing="xs">
                  <Stat label="Exit slot" value={<Text size="sm">{formatSlot(close.slot)}</Text>} />
                  <Stat
                    label="Exit price"
                    value={amount(
                      close.executionPrice,
                      usdtClose?.executionPriceUsdt,
                      usdtClose?.unavailableReason,
                    )}
                  />
                  <Stat
                    label="Gross proceeds"
                    value={amount(
                      close.grossProceeds,
                      usdtClose?.grossProceedsUsdt,
                      usdtClose?.unavailableReason,
                    )}
                  />
                  <Stat
                    label={`Exit fees (${levelPercent(close.feePercent, close.feeBps)})`}
                    value={amount(close.fee, usdtClose?.feeUsdt, usdtClose?.unavailableReason)}
                  />
                  <Stat
                    label="Exit price impact"
                    value={<Bps value={close.priceImpactBps} percent={close.priceImpactPercent} />}
                  />
                  <Stat
                    label="Exit transaction cost"
                    value={amount(
                      close.transactionCost,
                      usdtClose?.transactionCostUsdt,
                      usdtClose?.unavailableReason,
                    )}
                  />
                  <Stat
                    label="Net proceeds"
                    value={amount(
                      close.netProceeds,
                      usdtClose?.netProceedsUsdt,
                      usdtClose?.unavailableReason,
                    )}
                  />
                  <Stat
                    label="Realized P&L"
                    value={
                      usdt ? (
                        <Usdt
                          value={usdtClose?.realizedPnlUsdt}
                          reason={usdtClose?.unavailableReason}
                          pnl
                        />
                      ) : (
                        <Pnl value={close.realizedPnl} size="md" />
                      )
                    }
                  />
                </SimpleGrid>
                {usdt && <RateNote rate={usdtClose?.rate} minute={usdtClose?.minute} />}
                <TradeLinks label="Exit trade" observationId={close.observationId} />
              </>
            ) : (
              <Alert color="yellow" variant="light" p="xs" title="Still open">
                <Text size="sm">
                  No exit: <Reason code={scenario.exitUnavailableReason} />
                </Text>
              </Alert>
            )}
          </>
        )}
      </Stack>
    </Card>
  );
}
