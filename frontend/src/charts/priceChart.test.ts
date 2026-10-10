import type { LineSeriesOption } from 'echarts';
import { describe, expect, it } from 'vitest';
import { sampleObservations } from '../mocks/fixtures';
import { observationSeries, observationTooltip, priceAxisLabel } from './priceChart';

describe('price chart', () => {
  it('escapes imported source text in the HTML tooltip', () => {
    const observation = {
      ...sampleObservations[0]!,
      source: '<img src=x onerror="alert(1)"> & fixture',
    };
    const tooltip = observationTooltip(observation);
    expect(tooltip).not.toContain('<img');
    expect(tooltip).toContain('&lt;img src=x onerror=&quot;alert(1)&quot;&gt; &amp; fixture');
    expect(tooltip).toContain('Price <b>0.000000095</b>');
  });

  it('breaks the price line at invalid observations instead of joining across them', () => {
    const observations = sampleObservations.slice(0, 3).map((observation, index) => ({
      ...observation,
      quality: index === 1 ? 'INVALID' : 'LAST_TRADE',
    }));
    const line = observationSeries(observations)[0] as LineSeriesOption;
    const data = line.data as Array<{ value: [number, number | null] }>;
    expect(data.map((point) => point.value)).toEqual([
      [1001, 0.000000095],
      [1002, null],
      [1003, 0.000000086],
    ]);
    expect(line.connectNulls).toBe(false);
  });

  it('labels the price axis without exponents, tiny prices in the compact form', () => {
    expect(priceAxisLabel(1.15e-7)).toBe('0.0₆115');
    expect(priceAxisLabel(0.000000065)).toBe('0.0₇65');
    expect(priceAxisLabel(212.34)).toBe('212.34');
  });
});
