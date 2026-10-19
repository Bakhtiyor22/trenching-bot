import { describe, expect, it } from 'vitest';
import { configDefaults } from '../../mocks/fixtures';
import { compareDecimals } from '../../lib/decimal';
import { formValuesFrom, toRunStartRequest } from './runForm';

describe('run form server decimals', () => {
  it('trims server scale while preserving untouched submitted values', () => {
    const config = {
      ...configDefaults.defaults,
      minMarketCapUsd: '10000.000000000000',
      transactionCostSol: '0.001000000000',
      dipPercent: '20.00',
    };
    const values = formValuesFrom(config, 'dataset');
    expect(values.minMarketCapUsd).toBe('10000');
    expect(values.transactionCostSol).toBe('0.001');
    expect(values.dipPercent).toBe('20');
    const request = toRunStartRequest(values);
    expect(compareDecimals(request.config?.minMarketCapUsd ?? '', config.minMarketCapUsd)).toBe(0);
    expect(
      compareDecimals(request.config?.transactionCostSol ?? '', config.transactionCostSol),
    ).toBe(0);
  });
});
