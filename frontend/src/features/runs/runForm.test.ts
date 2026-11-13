import { describe, expect, it } from 'vitest';
import { configDefaults } from '../../mocks/fixtures';
import { runPreset } from '../../mocks/setupHandlers';
import {
  MARKET_COVERAGE_ENDED,
  SELL_CHECK_NEEDS_TRADES,
  SIGNAL_SIZE_NEEDS_TRADES,
} from '../../lib/codes';
import { compareDecimals } from '../../lib/decimal';
import {
  formValuesFrom,
  placeFieldErrors,
  refusalText,
  refusedTradeRules,
  type RunFormValues,
  secondsText,
  secondsToMs,
  toRunStartRequest,
  tradeRuleConflicts,
  TRADE_RULES,
  tradeRulesOff,
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

describe('run form server field errors', () => {
  const allInputs = Object.keys(
    formValuesFrom(configDefaults.defaults, 'dataset'),
  ) as (keyof RunFormValues)[];
  const invalid = 'Simulation configuration is invalid';

  it('puts each clock reason on its seconds input, in seconds and in the form’s own words', () => {
    expect(
      placeFieldErrors(
        {
          dipWindowMs: 'must be an integer between 1000 and 20000',
          maxHoldMs: 'must be an integer of at least 1500',
          hardExitStartMs: 'must be set together with maxHoldMs',
        },
        allInputs,
      ),
    ).toEqual({
      inputs: {
        dipWindowSeconds: 'Must be between 1 and 20 s',
        maxHoldSeconds: 'Must be at least 1.5 s',
        hardExitStartSeconds: 'Set together with the maximum hold',
      },
      unplaced: [],
    });
    const holdAlone = { maxHoldMs: 'must be set together with hardExitStartMs' };
    expect(placeFieldErrors(holdAlone, allInputs).inputs).toEqual({
      maxHoldSeconds: 'Set together with the hard exit start',
    });
  });

  it('keeps a clock reason of another shape, saying its numbers are milliseconds', () => {
    expect(
      placeFieldErrors(
        { dipWindowMs: 'must be a multiple of 1000', maxHoldMs: 'is required' },
        allInputs,
      ).inputs,
    ).toEqual({
      dipWindowSeconds: 'must be a multiple of 1000 (in milliseconds)',
      maxHoldSeconds: 'is required',
    });
  });

  it('keeps the reason of any other input and sets aside, in order, the fields no input shows', () => {
    expect(
      placeFieldErrors(
        {
          feeBps: 'is not a configuration field',
          latencySlots: 'must be an integer between 1 and 150',
          presetId: 'is not a known preset',
        },
        allInputs,
      ),
    ).toEqual({
      inputs: { latencySlots: 'must be an integer between 1 and 150' },
      unplaced: [
        { field: 'feeBps', reason: 'is not a configuration field' },
        { field: 'presetId', reason: 'is not a known preset' },
      ],
    });
    expect(
      placeFieldErrors({ dipWindowMs: 'must be an integer between 1000 and 20000' }, ['datasetId']),
    ).toEqual({
      inputs: {},
      unplaced: [{ field: 'dipWindowMs', reason: 'must be an integer between 1000 and 20000' }],
    });
  });

  it('closes the backend message, which has no period, before each sentence it adds', () => {
    expect(refusalText(invalid, [], 0)).toBe(invalid);
    expect(refusalText(invalid, [], 1)).toBe(
      'Simulation configuration is invalid. See the highlighted field.',
    );
    expect(refusalText(invalid, [], 2)).toBe(
      'Simulation configuration is invalid. See the highlighted fields.',
    );
    expect(refusalText('Cannot reach the backend. Is it running?', [], 1)).toBe(
      'Cannot reach the backend. Is it running? See the highlighted field.',
    );
  });

  it('states the reason of each field no input shows, unless the message already names it', () => {
    expect(
      refusalText(
        invalid,
        [
          { field: 'presetId', reason: 'is not a known preset' },
          { field: 'feeBps', reason: 'is not a configuration field' },
        ],
        1,
      ),
    ).toBe(
      'Simulation configuration is invalid. The agreed strategy is not a known preset. feeBps is not a configuration field. See the highlighted field.',
    );
    const unknownPreset = 'Invalid request field=presetId reason=is not a known preset';
    expect(
      refusalText(unknownPreset, [{ field: 'presetId', reason: 'is not a known preset' }], 0),
    ).toBe(unknownPreset);
  });
});

describe('run form price measure and sell rule', () => {
  it('fills the preset POST_TRADE and on, the engine defaults TRADE_AVERAGE and off', () => {
    const preset = formValuesFrom(runPreset.config, 'dataset');
    expect([preset.priceMeasure, preset.followWalletSells]).toEqual(['POST_TRADE', true]);
    const defaults = formValuesFrom(configDefaults.defaults, 'dataset');
    expect([defaults.priceMeasure, defaults.followWalletSells]).toEqual(['TRADE_AVERAGE', false]);
  });

  it('always sends the measure as a JSON string and the sell rule as a JSON boolean', () => {
    const preset = JSON.parse(
      JSON.stringify(toRunStartRequest(formValuesFrom(runPreset.config, 'dataset'))),
    ) as { config: Record<string, unknown> };
    expect(preset.config.priceMeasure).toBe('POST_TRADE');
    expect(preset.config.followWalletSells).toBe(true);
    const defaults = toRunStartRequest(formValuesFrom(configDefaults.defaults, 'dataset'));
    expect(defaults.config).toMatchObject({
      priceMeasure: 'TRADE_AVERAGE',
      followWalletSells: false,
    });
  });
});

describe('run form rules that need trader data', () => {
  const agreed = formValuesFrom(runPreset.config, 'dataset');
  const engine = formValuesFrom(configDefaults.defaults, 'dataset');

  it('flags the sell rule, then the minimum signal size, only on a dataset that records no trades', () => {
    expect(tradeRuleConflicts(agreed, 3)).toEqual(['followWalletSells', 'minSignalSol']);
    expect([4, 5, 6].map((schema) => tradeRuleConflicts(agreed, schema))).toEqual([[], [], []]);
    expect(tradeRuleConflicts(agreed, undefined)).toEqual([]);
  });

  it('flags only the rules that are on', () => {
    expect(tradeRuleConflicts({ ...agreed, followWalletSells: false }, 3)).toEqual([
      'minSignalSol',
    ]);
    expect(tradeRuleConflicts({ ...agreed, minSignalSol: ' ' }, 3)).toEqual(['followWalletSells']);
    expect(tradeRuleConflicts(engine, 3)).toEqual([]);
  });

  it('turns off every rule still on after either refusal, not only the one it names', () => {
    expect(refusedTradeRules(agreed, SELL_CHECK_NEEDS_TRADES)).toEqual([
      'followWalletSells',
      'minSignalSol',
    ]);
    expect(refusedTradeRules(agreed, SIGNAL_SIZE_NEEDS_TRADES)).toEqual([
      'followWalletSells',
      'minSignalSol',
    ]);
    // Edited off since the refusal: the named rule is still the one to turn off.
    expect(refusedTradeRules(engine, SIGNAL_SIZE_NEEDS_TRADES)).toEqual(['minSignalSol']);
    expect(refusedTradeRules(agreed, MARKET_COVERAGE_ENDED)).toEqual([]);
    expect(refusedTradeRules(agreed, null)).toEqual([]);
  });

  it('turns the rules off as an unticked sell rule and a cleared minimum, sent as false and null', () => {
    expect(tradeRulesOff(['minSignalSol'])).toEqual({ minSignalSol: '' });
    const off = { ...agreed, ...tradeRulesOff(TRADE_RULES) };
    expect(tradeRuleConflicts(off, 3)).toEqual([]);
    expect(toRunStartRequest(off).config).toMatchObject({
      followWalletSells: false,
      minSignalSol: null,
    });
  });
});
