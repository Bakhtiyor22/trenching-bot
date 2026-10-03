import { describe, expect, it } from 'vitest';
import {
  compareToZero,
  decimalSign,
  formatDecimal,
  isDecimalString,
  pnlColor,
  toChartNumber,
  validatePositiveDecimal,
} from './decimal';

describe('formatDecimal', () => {
  it('keeps tiny values exactly, with no float rounding or exponent', () => {
    expect(formatDecimal('0.00000000001234')).toBe('0.00000000001234');
  });

  it('keeps every fraction digit including trailing zeros', () => {
    expect(formatDecimal('26.089701492540')).toBe('26.089701492540');
    expect(formatDecimal('0.100000000000')).toBe('0.100000000000');
  });

  it('groups only the integer part', () => {
    expect(formatDecimal('1234567.000000000001')).toBe('1,234,567.000000000001');
    expect(formatDecimal('-1234.5')).toBe('-1,234.5');
    expect(formatDecimal('999')).toBe('999');
  });

  it('can skip grouping', () => {
    expect(formatDecimal('1234567.89', { group: false })).toBe('1234567.89');
  });

  it('never loses digits beyond float precision', () => {
    const value = '12345678901234567890.123456789012345678';
    expect(formatDecimal(value, { group: false })).toBe(value);
    expect(formatDecimal(value).replace(/,/g, '')).toBe(value);
  });

  it('returns non-decimal input unchanged and a dash for null', () => {
    expect(formatDecimal('1e-11')).toBe('1e-11');
    expect(formatDecimal(null)).toBe('—');
  });
});

describe('decimalSign', () => {
  it('reads the sign from the digits', () => {
    expect(decimalSign('8.696567164180')).toBe(1);
    expect(decimalSign('-0.000000000001')).toBe(-1);
    expect(decimalSign('0.000000000000')).toBe(0);
    expect(decimalSign('-0.000')).toBe(0);
    expect(decimalSign('0.00000000001234')).toBe(1);
    expect(compareToZero('-26.089701492540')).toBe(-1);
  });

  it('treats invalid input as zero', () => {
    expect(decimalSign('abc')).toBe(0);
    expect(isDecimalString('1,000')).toBe(false);
  });
});

describe('pnlColor', () => {
  it('maps sign to a color', () => {
    expect(pnlColor('26.089701492540')).toBe('teal');
    expect(pnlColor('-1')).toBe('red');
    expect(pnlColor('0.000000000000')).toBe('dimmed');
  });
});

describe('toChartNumber', () => {
  it('converts for plotting only', () => {
    expect(toChartNumber('10.945')).toBe(10.945);
    expect(toChartNumber(null)).toBeNull();
    expect(toChartNumber('x')).toBeNull();
  });
});

describe('validatePositiveDecimal', () => {
  const rule = { maxFractionDigits: 12, maxIntegerDigits: 26 };

  it('accepts plain positive decimals', () => {
    expect(validatePositiveDecimal('100', rule)).toBeNull();
    expect(validatePositiveDecimal('0.5', rule)).toBeNull();
    expect(validatePositiveDecimal('0.000000000001', rule)).toBeNull();
    expect(validatePositiveDecimal('1.0000000000001', rule)).toBeNull();
    expect(validatePositiveDecimal('0.00000000000051', rule)).toBeNull();
    expect(validatePositiveDecimal('0.0000000000015', rule)).toBeNull();
    expect(validatePositiveDecimal('0.0000000000025', rule)).toBeNull();
    expect(validatePositiveDecimal('9'.repeat(26) + '.9999999999985', rule)).toBeNull();
  });

  it('rejects exponents, signs, rounded zero and values overflowing storage precision', () => {
    expect(validatePositiveDecimal('1e2', rule)).toMatch(/plain positive decimal/);
    expect(validatePositiveDecimal('-1', rule)).toMatch(/plain positive decimal/);
    expect(validatePositiveDecimal('0.000', rule)).toMatch(/greater than zero/);
    expect(validatePositiveDecimal('0.0000000000001', rule)).toMatch(/greater than zero/);
    expect(validatePositiveDecimal('0.0000000000005', rule)).toMatch(/greater than zero/);
    expect(validatePositiveDecimal('9'.repeat(26) + '.9999999999995', rule)).toMatch(
      /At most 26 digits before/,
    );
    expect(validatePositiveDecimal('1'.repeat(27), rule)).toMatch(/At most 26 digits before/);
    expect(validatePositiveDecimal('', rule)).toBe('Required');
  });
});
