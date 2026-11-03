import { Alert, Card, Divider, Group, SimpleGrid, Stack, Text, Tooltip } from '@mantine/core';
import type { ReactNode } from 'react';
import type { Config, Scenario, UsdtScenario } from '../../api/types';
import { Decimal, Pnl, Timestamp } from '../../components/DisplayValues';
import { MarketLinks } from '../../components/MarketLinks';
import { Stat } from '../../components/Panels';
import { CodeText } from '../../components/PlainLanguage';
import { StatusChip } from '../../components/StatusChip';
import { codeLabel, standingCurveLabel } from '../../lib/codes';
import { formatDecimal } from '../../lib/decimal';
import { levelPercent } from '../../lib/percent';
import { formatSlot, humanizeCode, shortId, signedBps, usdtRateNote } from '../../lib/format';
import { recordedTradeSignature, transactionLinks } from '../../lib/marketLinks';
import { closeReasonText, knownReasonText } from '../captures/captureReasons';
import { scenarioOutcome } from './scenarioOutcome';
import { watchEnd, watchEndText } from './watchEnd';

/** A reason in plain words, with the code below it for support. */
function Reason({ code }: { code: string | null }) {
  return (
    <Stack gap={2}>
      <Text size="sm">{knownReasonText(code) ?? humanizeCode(code)}</Text>
      <CodeText code={code} />
    </Stack>
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
      <Tooltip label={reason ?? 'No value'} withArrow disabled={!reason}>
        <Text span size="sm" c="dimmed">
          —{reason && ` ${codeLabel(reason)}`}
        </Text>
      </Tooltip>
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

function TradeLinks({ label, observationId }: { label: string; observationId: string | null }) {
  const signature = observationId ? recordedTradeSignature(observationId) : null;
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

/** The cycle's end state as a chip: take profit, stop loss, still open, or no entry. */
function OutcomeBadge({ scenario }: { scenario: Scenario }) {
  const outcome = scenarioOutcome(scenario);
  return (
    <StatusChip tone={outcome.tone} glyph={outcome.glyph}>
      {outcome.label}
    </StatusChip>
  );
}

/** Why the cycle never entered, with Decu's sell when that was the reason. */
function NoEntry({ scenario }: { scenario: Scenario }) {
  const sell = scenario.walletSell;
  if (scenario.rejectionReason === 'WALLET_SOLD' && sell)
    return (
      <Stack gap={4}>
        <Text size="sm">Decu sold before the dip</Text>
        <CodeText code="WALLET_SOLD" />
        <Text size="xs" c="dimmed">
          Sold at slot {formatSlot(sell.slot)}
        </Text>
        <TradeLinks label="Decu's sell" observationId={sell.eventId} />
      </Stack>
    );
  return <Reason code={scenario.rejectionReason} />;
}

function ExitTriggered({ position }: { position: NonNullable<Scenario['position']> }) {
  const trigger = position.exitTrigger;
  if (!trigger) return null;
  if (trigger.reason === 'TIME_EXIT')
    return (
      <Text size="sm">
        Time exit at <Timestamp iso={trigger.time ?? position.exitStart} />
      </Text>
    );
  // The trigger's slot is our fill's when Decu sold before our buy landed, so it is never called his sell's slot.
  if (trigger.reason === 'WALLET_SOLD')
    return (
      <Stack gap={0}>
        <Text size="sm">{closeReasonText(trigger.reason)}</Text>
        <Text size="xs" c="dimmed">
          Exit started at slot {formatSlot(trigger.slot)}
        </Text>
      </Stack>
    );
  return (
    <Text size="sm">
      {humanizeCode(trigger.reason)} at slot {formatSlot(trigger.slot)}
    </Text>
  );
}

/**
 * One dip-entry cycle: the tracked wallet's first buy of a token (P0), the dip that triggered the entry, the fill, the
 * take-profit and stop-loss levels and the exit. With [usdt], entry and exit amounts show the backend's USDT
 * conversion; P0 and the levels stay in the quote unit, since the strategy compares them there. [config] names the
 * run's price measure and dip window; without it the card reads as an untimed average-price run.
 */
export function ScenarioCard({
  scenario,
  usdt,
  unit,
  config,
  selected = false,
}: {
  scenario: Scenario;
  usdt?: UsdtScenario;
  unit: string;
  config?: Pick<Config, 'priceMeasure' | 'dipWindowMs'>;
  selected?: boolean;
}) {
  const { fill, position, trigger } = scenario;
  // The backend fills both prices with Decu's average as a placeholder; the real reference is unknown.
  const referenceUnknown = scenario.watch === 'REFERENCE_UNAVAILABLE';
  const postTrade = config?.priceMeasure === 'POST_TRADE';
  const watched = watchEndText(watchEnd(scenario, config));
  const unknown = (
    <Text size="sm" c="dimmed">
      Unknown
    </Text>
  );
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
      style={selected ? { borderColor: 'var(--console-accent)' } : undefined}
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
            First buy at slot {formatSlot(scenario.sourceSlot)}
            {watched && ` · ${watched}`}
          </Text>
        </Stack>
        <OutcomeBadge scenario={scenario} />
      </Group>

      <Stack gap="sm">
        <Divider label="Waiting for the dip" labelPosition="left" />
        <SimpleGrid cols={2} spacing="xs" verticalSpacing="xs">
          <Stat
            label={
              postTrade
                ? `Reference price (after Decu's buy) (${unit})`
                : `First buy price, P0 (${unit})`
            }
            value={referenceUnknown ? unknown : <Decimal value={scenario.referencePrice} />}
          />
          <Stat
            label={`Dip level (${unit})`}
            value={referenceUnknown ? unknown : <Decimal value={scenario.dipPrice} />}
          />
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
        <TradeLinks label="Decu's buy" observationId={scenario.referenceObservationId} />
        {trigger && <TradeLinks label="Dip trade" observationId={trigger.observationId} />}

        {!filled ? (
          <Alert color="gray" variant="light" p="xs" title="No entry">
            <NoEntry scenario={scenario} />
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
                    <StatusChip tone="neutral" glyph={position.state === 'OPEN' ? '◷' : undefined}>
                      {position.state === 'OPEN' ? 'Position open' : position.state === 'CLOSED' ? 'Position closed' : humanizeCode(position.state)}
                    </StatusChip>
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
                {position.exitStart && (
                  <Stat label="Exit start" value={<Timestamp iso={position.exitStart} />} />
                )}
                {position.exitTrigger && (
                  <Stat label="Exit triggered" value={<ExitTriggered position={position} />} />
                )}
              </SimpleGrid>
            )}
            {position?.exitTrigger && (
              <TradeLinks
                label={
                  position.exitTrigger.reason === 'WALLET_SOLD'
                    ? "Decu's sell"
                    : 'Exit trigger trade'
                }
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
                {close.priceAssumption === 'STANDING_CURVE' && (
                  <Text size="xs" c="dimmed">
                    {standingCurveLabel(close.reason)}
                  </Text>
                )}
                <TradeLinks
                  label={
                    close.priceAssumption === 'STANDING_CURVE' ? 'Curve from trade' : 'Exit trade'
                  }
                  observationId={close.observationId}
                />
              </>
            ) : (
              <Alert color="gray" variant="light" p="xs" title="Still open">
                <Reason code={scenario.exitUnavailableReason} />
              </Alert>
            )}
          </>
        )}
      </Stack>
    </Card>
  );
}
