import { Box, useComputedColorScheme } from '@mantine/core';
import { BarChart, CandlestickChart, CustomChart, LineChart, ScatterChart } from 'echarts/charts';
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  MarkPointComponent,
  TooltipComponent,
} from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import type { ECharts, EChartsOption } from 'echarts';
import { useEffect, useRef } from 'react';
import { chartTheme } from '../charts/chartTokens';
import { terminal, terminalLight } from '../theme';
import ReactEChartsCore from './chartComponent';

// Register only what the console uses, to keep the bundle small.
echarts.use([
  BarChart,
  CandlestickChart,
  CustomChart,
  LineChart,
  ScatterChart,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  MarkPointComponent,
  TooltipComponent,
  CanvasRenderer,
]);

const DARK_THEME = 'terminal-dark';
const LIGHT_THEME = 'terminal-light';

echarts.registerTheme(DARK_THEME, chartTheme(terminal));
echarts.registerTheme(LIGHT_THEME, chartTheme(terminalLight));

export type ChartEvents = Record<string, (params: unknown) => void>;

export interface ChartProps {
  option: EChartsOption;
  height?: number | string;
  /** Accessible description of what the chart shows. */
  ariaLabel: string;
  onEvents?: ChartEvents;
  onReady?: (instance: ECharts) => void;
  onDispose?: () => void;
  notMerge?: boolean;
  replaceMerge?: string[];
}

/**
 * ECharts wrapper. Charts are the only place where decimal strings become JS numbers; tables and
 * tooltips that show amounts use the original strings.
 */
export function Chart({
  option,
  height = 300,
  ariaLabel,
  onEvents,
  onReady,
  onDispose,
  notMerge = true,
  replaceMerge,
}: ChartProps) {
  const dispose = useRef(onDispose);
  useEffect(() => {
    dispose.current = onDispose;
  }, [onDispose]);
  useEffect(() => () => dispose.current?.(), []);
  const scheme = useComputedColorScheme('dark');
  // No entry animation by default: options are rebuilt on every render of a polled page, and replaying
  // the grow-in each time can leave bars invisible. A chart that wants motion sets `animation` itself.
  const themed: EChartsOption = {
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'inherit' },
    animation: false,
    ...option,
  };
  return (
    <Box role="img" aria-label={ariaLabel} data-testid="chart" style={{ height }}>
      <ReactEChartsCore
        echarts={echarts}
        option={themed}
        theme={scheme === 'dark' ? DARK_THEME : LIGHT_THEME}
        notMerge={notMerge}
        replaceMerge={replaceMerge}
        lazyUpdate
        style={{ height, width: '100%' }}
        onEvents={onEvents}
        onChartReady={onReady}
      />
    </Box>
  );
}
