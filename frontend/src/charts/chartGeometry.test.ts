import type { ECharts } from 'echarts';
import { describe, expect, it, vi } from 'vitest';
import { chartContainsPoint, chartGeometryReady } from './chartGeometry';

describe('chart geometry', () => {
  it('waits for lazy chart geometry instead of querying unsupported grid containment', () => {
    const containPixel = vi.fn();
    const chart = {
      getModel: () => ({ getComponent: () => ({}) }),
      containPixel,
    } as unknown as ECharts;
    expect(chartGeometryReady(chart)).toBe(false);
    expect(chartContainsPoint(chart, [50, 50])).toBe(false);
    expect(containPixel).not.toHaveBeenCalled();
  });
  it('clips pointers to the rendered grid bounds', () => {
    const chart = {
      getModel: () => ({
        getComponent: () => ({
          coordinateSystem: { getRect: () => ({ x: 10, y: 20, width: 200, height: 100 }) },
        }),
      }),
    } as unknown as ECharts;
    expect(chartGeometryReady(chart)).toBe(true);
    expect(chartContainsPoint(chart, [10, 20])).toBe(true);
    expect(chartContainsPoint(chart, [210, 120])).toBe(true);
    expect(chartContainsPoint(chart, [211, 120])).toBe(false);
  });
});
