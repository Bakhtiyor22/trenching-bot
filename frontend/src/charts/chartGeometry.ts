import type { ECharts } from 'echarts';

export interface ChartGridRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

type GridChart = {
  getModel?: () => {
    getComponent: (
      name: string,
      index: number,
    ) => { coordinateSystem?: { getRect: () => ChartGridRect } } | undefined;
  };
};

export function chartGridRect(chart: ECharts): ChartGridRect | null {
  return (
    (chart as unknown as GridChart)
      .getModel?.()
      .getComponent('grid', 0)
      ?.coordinateSystem?.getRect() ?? null
  );
}

/** Lazy option updates temporarily have models but no coordinate system. */
export function chartGeometryReady(chart: ECharts): boolean {
  return (
    typeof (chart as unknown as GridChart).getModel !== 'function' || chartGridRect(chart) !== null
  );
}

export function chartContainsPoint(chart: ECharts, pixel: number[]): boolean {
  const rect = chartGridRect(chart);
  if (!rect) return chartGeometryReady(chart) && chart.containPixel({ gridIndex: 0 }, pixel);
  const [x, y] = pixel;
  return (
    x !== undefined &&
    y !== undefined &&
    x >= rect.x &&
    x <= rect.x + rect.width &&
    y >= rect.y &&
    y <= rect.y + rect.height
  );
}
