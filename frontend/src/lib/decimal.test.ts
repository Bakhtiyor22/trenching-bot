import { describe, expect, it } from 'vitest';
import {
  addDecimals,
  compactDecimal,
  compareDecimals,
  compareToZero,
  decimalSign,
  formatDecimal,
  isDecimalString,
  pnlColor,
  toChartNumber,
  trimFractionZeros,
  validateDecimal,
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

describe('validateDecimal', () => {
  const notional = { maxFractionDigits: 9, maxIntegerDigits: 26, min: '0.000000001' };
  const cost = { maxFractionDigits: 12, maxIntegerDigits: 26, min: '0', max: '0.1' };

  it('accepts whole lamports, ignoring trailing zeros', () => {
    expect(validateDecimal('100', notional)).toBeNull();
    expect(validateDecimal('0.5', notional)).toBeNull();
    expect(validateDecimal('0.000000001', notional)).toBeNull();
    expect(validateDecimal('0.100000000000', notional)).toBeNull();
    expect(validateDecimal('9'.repeat(26) + '.999999999', notional)).toBeNull();
  });

  it('rejects exponents, signs, sub-lamport digits, zero and values overflowing storage precision', () => {
    expect(validateDecimal('1e2', notional)).toMatch(/plain decimal/);
    expect(validateDecimal('-1', notional)).toMatch(/plain decimal/);
    expect(validateDecimal('0.0000000015', notional)).toBe('At most 9 decimals');
    expect(validateDecimal('0.000', notional)).toBe('Must be at least 0.000000001');
    expect(validateDecimal('1'.repeat(27), notional)).toMatch(/At most 26 digits before/);
    expect(validateDecimal('', notional)).toBe('Required');
  });

  it('accepts zero up to an inclusive maximum, compared exactly', () => {
    expect(validateDecimal('0', cost)).toBeNull();
    expect(validateDecimal('0.000000000001', cost)).toBeNull();
    expect(validateDecimal('0.1', cost)).toBeNull();
    expect(validateDecimal('0.100000000001', cost)).toBe('Must be between 0 and 0.1');
    expect(validateDecimal('0.0000000000001', cost)).toBe('At most 12 decimals');
  });
});

describe('compactDecimal', () => {
  it('folds the leading fractional zeros of a tiny price into a subscript count, keeping every digit', () => {
    expect(compactDecimal('0.00000008643')).toBe('0.0₇8643');
    expect(compactDecimal('0.000000112359')).toBe('0.0₆112359');
    expect(compactDecimal('-0.0000000734655')).toBe('-0.0₇734655');
    expect(compactDecimal('0.0000000000124017')).toBe('0.0₁₀124017');
  });

  it('leaves short, large, zero and non-decimal values to formatDecimal', () => {
    expect(compactDecimal('0.000123')).toBe('0.000123');
    expect(compactDecimal('1234.5')).toBe('1,234.5');
    expect(compactDecimal('0.000000000')).toBe('0.000000000');
    expect(compactDecimal('1e-9')).toBe('1e-9');
    expect(compactDecimal(null)).toBe('—');
  });
});

describe('trimFractionZeros', () => {
  it('drops trailing fractional zeros only', () => {
    expect(trimFractionZeros('212.34000000')).toBe('212.34');
    expect(trimFractionZeros('200.000')).toBe('200');
    expect(trimFractionZeros('200')).toBe('200');
    expect(trimFractionZeros('n/a')).toBe('n/a');
  });
});

describe('addDecimals', () => {
  it('adds exactly at the largest scale', () => {
    expect(addDecimals('26.089701492540', '-3.580241077133')).toBe('22.509460415407');
    expect(addDecimals('32.157983339118', '-38.283768656717')).toBe('-6.125785317599');
    expect(addDecimals('0.1', '0.2')).toBe('0.3');
    expect(addDecimals('-0.05', '0.01')).toBe('-0.04');
    expect(addDecimals('5', '7')).toBe('12');
    expect(addDecimals()).toBe('0');
  });

  it('refuses values that are not plain decimals', () => {
    expect(() => addDecimals('1e-9')).toThrow('Not a plain decimal: 1e-9');
  });
});

describe('compareDecimals', () => {
  it('orders exactly, beyond float precision', () => {
    expect(compareDecimals('26.089701492540', '-3.580241077133')).toBe(1);
    expect(compareDecimals('-3.58', '-3.580')).toBe(0);
    expect(compareDecimals('0.10000000000000000001', '0.1')).toBe(1);
    expect(compareDecimals('-1', '+2')).toBe(-1);
  });
});
