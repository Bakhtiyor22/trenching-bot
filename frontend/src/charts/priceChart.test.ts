import type { LineSeriesOption } from 'echarts';
import { describe, expect, it } from 'vitest';
import { sampleObservations } from '../mocks/fixtures';
import { observationSeries, observationTooltip } from './priceChart';

describe('price chart', () => {
  it('escapes imported source text in the HTML tooltip', () => {
    const observation = {
      ...sampleObservations[0]!,
      source: '<img src=x onerror="alert(1)"> & fixture',
    };
    const tooltip = observationTooltip(observation);
    expect(tooltip).not.toContain('<img');
    expect(tooltip).toContain('&lt;img src=x onerror=&quot;alert(1)&quot;&gt; &amp; fixture');
    expect(tooltip).toContain('Price <b>10</b>');
  });

  it('breaks the price line at invalid observations instead of joining across them', () => {
    const observations = sampleObservations.slice(0, 3).map((observation, index) => ({
      ...observation,
      quality: index === 1 ? 'INVALID' : 'LAST_TRADE',
    }));
    const line = observationSeries(observations)[0] as LineSeriesOption;
    const data = line.data as Array<{ value: [number, number | null] }>;
    expect(data.map((point) => point.value)).toEqual([
      [101, 10],
      [102, null],
      [103, 10],
    ]);
    expect(line.connectNulls).toBe(false);
  });
});
