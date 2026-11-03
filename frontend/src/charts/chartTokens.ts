import { useComputedColorScheme } from '@mantine/core';
import { FONT_MONO, tokensFor, type TerminalTokens } from '../theme';

/**
 * The console chart theme for one scheme: hairline solid axes and grid, mono axis labels, an amber
 * axis pointer and a transparent background. Charts colour their own series from the same tokens.
 */
export function chartTheme(t: TerminalTokens) {
  const axis = {
    axisLine: { lineStyle: { color: t.line } },
    axisTick: { lineStyle: { color: t.line } },
    axisLabel: { color: t.dimmed, fontFamily: FONT_MONO, fontSize: 11 },
    splitLine: { lineStyle: { color: t.grid, type: 'solid' } },
    nameTextStyle: { color: t.dimmed },
  };
  return {
    color: [t.accent, t.info, t.gain, t.loss, t.violet, t.dimmed],
    backgroundColor: 'transparent',
    textStyle: { color: t.text },
    categoryAxis: axis,
    valueAxis: axis,
    timeAxis: axis,
    legend: { textStyle: { color: t.dimmed } },
    tooltip: {
      backgroundColor: t.panel,
      borderColor: t.lineStrong,
      textStyle: { color: t.text, fontFamily: FONT_MONO, fontSize: 12 },
      axisPointer: { lineStyle: { color: t.accent }, crossStyle: { color: t.accent } },
    },
    dataZoom: {
      borderColor: t.line,
      fillerColor: 'rgba(242,180,60,0.12)',
      handleStyle: { color: t.accent, borderColor: t.accent },
      moveHandleStyle: { color: t.raised },
      dataBackground: { lineStyle: { color: t.lineStrong }, areaStyle: { color: t.raised } },
      textStyle: { color: t.dimmed },
    },
  };
}

/** The chart tokens of the scheme in use, for series colours inside an option. */
export function useChartTokens(): TerminalTokens {
  return tokensFor(useComputedColorScheme('dark'));
}

