import { Box, useComputedColorScheme } from '@mantine/core';
import { BarChart, LineChart, ScatterChart } from 'echarts/charts';
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
import type { EChartsOption } from 'echarts';
import ReactEChartsCore from './chartComponent';

// Register only what the console uses, to keep the bundle small.
echarts.use([
  BarChart,
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

export type ChartEvents = Record<string, (params: unknown) => void>;

export interface ChartProps {
  option: EChartsOption;
  height?: number;
  /** Accessible description of what the chart shows. */
  ariaLabel: string;
  onEvents?: ChartEvents;
}

/**
 * ECharts wrapper. Charts are the only place where decimal strings become JS numbers; tables and
 * tooltips that show amounts use the original strings.
 */
export function Chart({ option, height = 300, ariaLabel, onEvents }: ChartProps) {
  const scheme = useComputedColorScheme('light');
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
        theme={scheme === 'dark' ? 'dark' : undefined}
        notMerge
        lazyUpdate
        style={{ height, width: '100%' }}
        onEvents={onEvents}
      />
    </Box>
  );
}
