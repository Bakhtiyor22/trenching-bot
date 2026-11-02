import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Scenario } from '../../api/types';
import {
  REFERENCE_PLACEHOLDER,
  referenceUnavailableScenario,
  sampleScenarios,
  soldBeforeDipScenario,
  standingTakeProfitScenario,
  timeExitScenario,
  WALLET_SELL_SIGNATURES,
  walletSellRunDetail,
  walletSoldScenario,
} from '../../mocks/fixtures';
import { renderPage } from '../../test/render';
import { ScenarioCard } from './ScenarioCard';

function renderCard(scenario: Scenario, config?: typeof walletSellRunDetail.config) {
  return renderPage(<ScenarioCard scenario={scenario} unit="SOL" config={config} />);
}

const agreed = walletSellRunDetail.config;
const [, decuBuy, beforeDipBuy, holdingSell, beforeDipSell] = WALLET_SELL_SIGNATURES;

/** The Solscan link next to a "label" line of the card. */
function solscanNear(label: string) {
  const line = screen.getByText(label).parentElement as HTMLElement;
  return within(line).getByRole('link', { name: 'Solscan' });
}

describe('ScenarioCard exits', () => {
  it('shows a time exit with its exit start and the standing-curve note', () => {
    renderCard(timeExitScenario);
    expect(screen.getAllByText('Time exit').length).toBeGreaterThan(0);
    expect(screen.getByText('Exit start')).toBeInTheDocument();
    expect(screen.getByText(/Time exit at/)).toBeInTheDocument();
    expect(screen.getByText('Sold against the curve at the exit time')).toBeInTheDocument();
  });

  it('explains a take profit sold against the standing curve by its 60 s rule, never by the exit time', () => {
    renderCard(standingTakeProfitScenario);
    expect(
      screen.getByText('No trade within 60 s — sold against the curve at the trigger'),
    ).toBeInTheDocument();
    expect(screen.queryByText('Sold against the curve at the exit time')).not.toBeInTheDocument();
  });

  it('shows no standing-curve note for a sale into the next trade', () => {
    renderCard(sampleScenarios[0] as Scenario);
    expect(screen.queryByText(/sold against the curve/)).not.toBeInTheDocument();
    expect(screen.queryByText('Exit start')).not.toBeInTheDocument();
  });

  it('explains an unresolved open position and a buy below the minimum market cap', () => {
    const takeProfit = sampleScenarios[0] as Scenario;
    renderCard({
      ...takeProfit,
      position: {
        ...(takeProfit.position as NonNullable<Scenario['position']>),
        state: 'OPEN',
        close: null,
      },
      exitUnavailableReason: 'UNRESOLVED',
    });
    expect(
      screen.getByText(/The exit was triggered, but no curve could pay the sale/),
    ).toBeInTheDocument();
    expect(screen.getByText('UNRESOLVED')).toBeInTheDocument();
  });

  it('explains a skip because Decu bought at or below the minimum market cap', () => {
    renderCard({
      ...(sampleScenarios[2] as Scenario),
      watch: 'REFERENCE_BELOW_MIN_MARKET_CAP',
      rejectionReason: 'REFERENCE_BELOW_MIN_MARKET_CAP',
    });
    expect(
      screen.getByText(/Decu's buy was valued at or below the minimum market cap/),
    ).toBeInTheDocument();
  });
});

describe('ScenarioCard post-trade reference and Decu sells', () => {
  it("labels the post-trade reference and links Decu's buy it was read from", () => {
    renderCard(walletSoldScenario, agreed);
    expect(screen.getByText("Reference price (after Decu's buy) (SOL)")).toBeInTheDocument();
    expect(screen.queryByText(/First buy price, P0/)).not.toBeInTheDocument();
    expect(solscanNear("Decu's buy")).toHaveAttribute('href', `https://solscan.io/tx/${decuBuy}`);
  });

  it('keeps the first-buy label under average prices', () => {
    renderCard(sampleScenarios[0] as Scenario, { ...agreed, priceMeasure: 'TRADE_AVERAGE' });
    expect(screen.getByText('First buy price, P0 (SOL)')).toBeInTheDocument();
    expect(screen.queryByText("Decu's buy")).not.toBeInTheDocument();
  });

  it("shows a cycle Decu sold before the dip with the sell's slot and link", () => {
    renderCard(soldBeforeDipScenario, agreed);
    const noEntry = within(screen.getByRole('alert'));
    expect(noEntry.getByText(/Decu sold before the dip/)).toBeInTheDocument();
    expect(noEntry.getByText('Sold at slot 3,005')).toBeInTheDocument();
    expect(solscanNear("Decu's sell")).toHaveAttribute(
      'href',
      `https://solscan.io/tx/${beforeDipSell}`,
    );
    expect(solscanNear("Decu's buy")).toHaveAttribute(
      'href',
      `https://solscan.io/tx/${beforeDipBuy}`,
    );
    expect(screen.getByText(/watched until Decu sold at slot 3,005/)).toBeInTheDocument();
  });

  it('shows the reference and dip level as unknown, never the placeholder, when Decu’s buy is missing', () => {
    renderCard(referenceUnavailableScenario, agreed);
    expect(screen.getAllByText('Unknown')).toHaveLength(2);
    expect(screen.queryByText(REFERENCE_PLACEHOLDER)).not.toBeInTheDocument();
    expect(screen.queryByText(/0\.0000000794/)).not.toBeInTheDocument();
    expect(
      screen.getByText(/Decu's buy is not in the recorded market, so the reference is unknown\./),
    ).toBeInTheDocument();
    expect(screen.queryByText(/watched/)).not.toBeInTheDocument();
  });

  it("shows a Decu-sold exit at its start slot and links his sell, never calling that slot his sell's", () => {
    renderCard(walletSoldScenario, agreed);
    expect(screen.getAllByText('Sold because Decu sold').length).toBeGreaterThan(0);
    expect(screen.getByText('Exit started at slot 2,003')).toBeInTheDocument();
    expect(screen.queryByText(/Decu sold at slot/)).not.toBeInTheDocument();
    expect(solscanNear("Decu's sell")).toHaveAttribute(
      'href',
      `https://solscan.io/tx/${holdingSell}`,
    );
  });
});

describe('ScenarioCard watch end', () => {
  it('names the dip window instead of the horizon when the window ended the watch', () => {
    renderCard(sampleScenarios[2] as Scenario, agreed);
    expect(screen.getByText(/watched for the 30 s dip window/)).toBeInTheDocument();
    expect(screen.queryByText(/watched through slot/)).not.toBeInTheDocument();
  });

  it('shows the dip slot for a triggered cycle and the horizon for an untimed run', () => {
    const { unmount } = renderCard(sampleScenarios[0] as Scenario, agreed);
    expect(screen.getByText(/watched until the dip at slot 1,002/)).toBeInTheDocument();
    unmount();
    renderCard(sampleScenarios[2] as Scenario);
    expect(screen.getByText(/watched through slot 7,500/)).toBeInTheDocument();
  });
});
