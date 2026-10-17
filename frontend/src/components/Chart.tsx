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
import { FONT_MONO, terminal } from '../theme';
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

const TERMINAL_THEME = 'terminal';

const axis = {
  axisLine: { lineStyle: { color: terminal.line } },
  axisTick: { lineStyle: { color: terminal.line } },
  axisLabel: { color: terminal.dimmed, fontFamily: FONT_MONO, fontSize: 11 },
  splitLine: { lineStyle: { color: '#161D23' } },
  nameTextStyle: { color: terminal.dimmed },
};

// The dark console theme: the canvas palette, hairline axes and mono axis labels.
echarts.registerTheme(TERMINAL_THEME, {
  color: [
    terminal.accent,
    terminal.info,
    terminal.gain,
    terminal.loss,
    terminal.violet,
    terminal.dimmed,
  ],
  backgroundColor: 'transparent',
  textStyle: { color: terminal.text },
  categoryAxis: axis,
  valueAxis: axis,
  timeAxis: axis,
  legend: { textStyle: { color: terminal.dimmed } },
  tooltip: {
    backgroundColor: terminal.ground,
    borderColor: '#2A343D',
    textStyle: { color: terminal.text, fontFamily: FONT_MONO, fontSize: 12 },
    axisPointer: { lineStyle: { color: terminal.accent }, crossStyle: { color: terminal.accent } },
  },
  dataZoom: {
    borderColor: terminal.line,
    fillerColor: 'rgba(242,180,60,0.12)',
    handleStyle: { color: terminal.accent, borderColor: terminal.accent },
    moveHandleStyle: { color: terminal.raised },
    dataBackground: { lineStyle: { color: '#2A343D' }, areaStyle: { color: terminal.raised } },
    textStyle: { color: terminal.dimmed },
  },
});

export type ChartEvents = Record<string, (params: unknown) => void>;

export interface ChartProps {
  option: EChartsOption;
  height?: number | string;
  /** Accessible description of what the chart shows. */
  ariaLabel: string;
  onEvents?: ChartEvents;
  onReady?: (instance: ECharts) => void;
  onDispose?: () => void;
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
}: ChartProps) {
  const dispose = useRef(onDispose);
  useEffect(() => {
    dispose.current = onDispose;
  }, [onDispose]);
  useEffect(() => () => dispose.current?.(), []);
  const scheme = useComputedColorScheme('dark');
  const themed: EChartsOption = {
    backgroundColor: 'transparent',
    textStyle: { fontFamily: 'inherit' },
    ...option,
  };
  return (
    <Box role="img" aria-label={ariaLabel} data-testid="chart">
      <ReactEChartsCore
        echarts={echarts}
        option={themed}
        theme={scheme === 'dark' ? TERMINAL_THEME : undefined}
        notMerge
        lazyUpdate
        style={{ height, width: '100%' }}
        onEvents={onEvents}
        onChartReady={onReady}
      />
    </Box>
  );
}
