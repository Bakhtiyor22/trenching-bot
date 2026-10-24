import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Scenario } from '../../api/types';
import {
  sampleScenarios,
  standingTakeProfitScenario,
  timeExitScenario,
} from '../../mocks/fixtures';
import { renderPage } from '../../test/render';
import { ScenarioCard } from './ScenarioCard';

function renderCard(scenario: Scenario) {
  return renderPage(<ScenarioCard scenario={scenario} unit="SOL" />);
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
