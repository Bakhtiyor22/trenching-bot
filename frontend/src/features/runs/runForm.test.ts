import { describe, expect, it } from 'vitest';
import { configDefaults } from '../../mocks/fixtures';
import { runPreset } from '../../mocks/setupHandlers';
import { compareDecimals } from '../../lib/decimal';
import {
  formValuesFrom,
  secondsText,
  secondsToMs,
  toRunStartRequest,
  validateRunForm,
} from './runForm';

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

describe('run form clocks', () => {
  const values = formValuesFrom(configDefaults.defaults, 'dataset');

  it('converts typed seconds to whole milliseconds exactly and back', () => {
    expect(secondsToMs('30')).toBe(30000);
    expect(secondsToMs('0.5')).toBe(500);
    expect(secondsToMs('1.234')).toBe(1234);
    expect(secondsToMs('')).toBeNull();
    expect(secondsToMs('1.2345')).toBeUndefined();
    expect(secondsText(30000)).toBe('30');
    expect(secondsText(500)).toBe('0.5');
    expect(secondsText(null)).toBe('');
  });

  it('fills the preset clocks in seconds and sends them in milliseconds, empty as null', () => {
    const preset = formValuesFrom(runPreset.config, 'dataset');
    expect([preset.dipWindowSeconds, preset.maxHoldSeconds, preset.hardExitStartSeconds]).toEqual([
      '30',
      '180',
      '240',
    ]);
    expect(toRunStartRequest(preset).config).toMatchObject({
      dipWindowMs: 30000,
      maxHoldMs: 180000,
      hardExitStartMs: 240000,
    });
    expect(toRunStartRequest(values).config).toMatchObject({
      dipWindowMs: null,
      maxHoldMs: null,
      hardExitStartMs: null,
    });
  });

  it('shows ranges in seconds and puts the pair rule on the empty field', () => {
    const limits = configDefaults.limits;
    expect(
      validateRunForm({ ...values, dipWindowSeconds: '1.2345' }, limits).dipWindowSeconds,
    ).toBe('Use whole seconds or up to 3 decimals');
    expect(validateRunForm({ ...values, dipWindowSeconds: '601' }, limits).dipWindowSeconds).toBe(
      'Must be between 1 and 600 s',
    );
    expect(validateRunForm({ ...values, maxHoldSeconds: '3601' }, limits).maxHoldSeconds).toBe(
      'Must be between 1 and 3600 s',
    );
    const holdOnly = validateRunForm({ ...values, maxHoldSeconds: '180' }, limits);
    expect(holdOnly.hardExitStartSeconds).toBe('Set together with the maximum hold');
    expect(holdOnly.maxHoldSeconds).toBeUndefined();
    const hardOnly = validateRunForm({ ...values, hardExitStartSeconds: '240' }, limits);
    expect(hardOnly.maxHoldSeconds).toBe('Set together with the hard exit start');
  });
});
