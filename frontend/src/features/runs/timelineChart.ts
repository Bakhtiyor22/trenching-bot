import type { EChartsOption, SeriesOption } from 'echarts';
import { pairKey } from '../../api/endpoints';
import type { Observation, RunUsdtView, Scenario } from '../../api/types';
import { itemTooltip, observationSeries, slotAxes } from '../../charts/priceChart';
import { compactDecimal, formatDecimal, toChartNumber } from '../../lib/decimal';
import { formatSlot, humanizeCode, signedBps, usdtRateNote } from '../../lib/format';
import { FONT_MONO, terminal } from '../../theme';

export const ENTRY_COLOR = terminal.info;
export const EXIT_COLOR = terminal.rejected;
export const REJECTED_COLOR = terminal.loss;
export const SOURCE_COLOR = terminal.dimmed;
/** Legend name shared by the level lines, so one click hides or shows them all. */
export const LEVELS_SERIES = 'Levels';
export const LEVEL_COLORS = { reference: '#C3CDD5', dip: terminal.info, takeProfit: terminal.gain, stopLoss: terminal.loss } as const;

export interface PriceMarker {
  slot: number;
  /** Exact price string (shown in tooltips); plotted via `toChartNumber`. */
  price: string;
  label: string;
  tooltip: string;
}

export interface SlotMarker {
  slot: number;
  label: string;
}

/** A horizontal price level: the first buy's price, the dip level, take profit or stop loss. */
export interface LevelMarker {
  price: string;
  label: string;
  color: string;
}

export interface TimelineMarkers {
  sourceBuys: SlotMarker[];
  entries: PriceMarker[];
  exits: PriceMarker[];
  rejections: SlotMarker[];
  levels: LevelMarker[];
}

/** Which prices the timeline plots, and in which unit. A null price leaves that point or marker out. */
export interface TimelineValues {
  unit: string;
  observationPrice: (observation: Observation) => string | null;
  entryPrice: (scenario: Scenario) => string | null;
  exitPrice: (scenario: Scenario) => string | null;
  costBasis: (scenario: Scenario) => string | null;
  netProceeds: (scenario: Scenario) => string | null;
  realizedPnl: (scenario: Scenario) => string | null;
  /** Provenance lines (the rate and minute of a conversion); null for stored values. */
  observationNote: (observation: Observation) => string | null;
  entryNote: (scenario: Scenario) => string | null;
  exitNote: (scenario: Scenario) => string | null;
  /** A strategy level (P0, dip, take profit, stop loss) in this unit, or null when it has no single value in it. */
  level: (price: string) => string | null;
}

/** The stored prices, in the run's quote unit. */
export function quoteValues(unit: string): TimelineValues {
  return {
    unit,
    observationPrice: (o) => o.price,
    entryPrice: (s) => s.fill?.executionPrice ?? null,
    exitPrice: (s) => s.position?.close?.executionPrice ?? null,
    costBasis: (s) => s.fill?.costBasis ?? null,
    netProceeds: (s) => s.position?.close?.netProceeds ?? null,
    realizedPnl: (s) => s.position?.close?.realizedPnl ?? null,
    observationNote: () => null,
    entryNote: () => null,
    exitNote: () => null,
    level: (price) => price,
  };
}

/** The same prices converted to USDT by the backend, each at its own minute's rate. */
export function usdtValues(view: RunUsdtView): TimelineValues {
  const observations = new Map(view.observations.map((o) => [o.id, o]));
  const scenarios = new Map(view.scenarios.map((s) => [s.intentId, s]));
  const fill = (s: Scenario) => scenarios.get(s.intentId)?.fill;
  const close = (s: Scenario) => scenarios.get(s.intentId)?.close;
  return {
    unit: 'USDT',
    observationPrice: (o) => observations.get(o.id)?.priceUsdt ?? null,
    entryPrice: (s) => fill(s)?.executionPriceUsdt ?? null,
    exitPrice: (s) => close(s)?.executionPriceUsdt ?? null,
    costBasis: (s) => fill(s)?.costBasisUsdt ?? null,
    netProceeds: (s) => close(s)?.netProceedsUsdt ?? null,
    realizedPnl: (s) => close(s)?.realizedPnlUsdt ?? null,
    observationNote: (o) => usdtRateNote(observations.get(o.id)?.rate, observations.get(o.id)?.minute),
    entryNote: (s) => usdtRateNote(fill(s)?.rate, fill(s)?.minute),
    exitNote: (s) => usdtRateNote(close(s)?.rate, close(s)?.minute),
    // The strategy compares levels in SOL; each trade converts at its own minute, so a level has no one USDT value.
    level: () => null,
  };
}

/** Markers for the cycle of one pair: its first buy, price levels, entry fill and exit, or why it never entered. */
export function timelineMarkers(scenarios: Scenario[], values: TimelineValues = quoteValues('')): TimelineMarkers {
  const unit = values.unit ? ` ${values.unit}` : '';
  const sourceSlots = [...new Set(scenarios.map((s) => s.sourceSlot))].sort((a, b) => a - b);
  const entries: PriceMarker[] = [];
  const exits: PriceMarker[] = [];
  const rejections: SlotMarker[] = [];
  const levels: LevelMarker[] = [];
  const addLevel = (price: string, label: string, color: string) => {
    const converted = values.level(price);
    if (converted != null) levels.push({ price: converted, label, color });
  };
  for (const s of scenarios) {
    addLevel(s.referencePrice, 'P0', LEVEL_COLORS.reference);
    addLevel(s.dipPrice, 'Dip', LEVEL_COLORS.dip);
    if (s.position) {
      addLevel(s.position.takeProfitPrice, 'Take profit', LEVEL_COLORS.takeProfit);
      addLevel(s.position.stopLossPrice, 'Stop loss', LEVEL_COLORS.stopLoss);
    }
    if (s.status === 'REJECTED' || !s.fill) {
      rejections.push({
        slot: s.targetSlot,
        label: `No entry: ${humanizeCode(s.rejectionReason)}`,
      });
      continue;
    }
    const entryPrice = values.entryPrice(s);
    if (entryPrice != null) {
      entries.push({
        slot: s.fill.actualSlot,
        price: entryPrice,
        label: 'entry',
        tooltip: [
          `<b>Entry</b> (first buy at slot ${formatSlot(s.sourceSlot)})`,
          `Dip at slot ${formatSlot(s.targetSlot)}, filled at slot ${formatSlot(s.fill.actualSlot)}`,
          `Execution price <b>${formatDecimal(entryPrice)}</b>${unit}`,
          `Quantity ${formatDecimal(s.fill.quantity)} · cost ${formatDecimal(values.costBasis(s))}${unit}`,
          `Fees ${s.fill.feeBps} bps · impact ${signedBps(s.fill.priceImpactBps)}`,
          ...noteLine(values.entryNote(s)),
        ].join('<br/>'),
      });
    }
    const close = s.position?.close;
    const exitPrice = values.exitPrice(s);
    if (close && exitPrice != null) {
      exits.push({
        slot: close.slot,
        price: exitPrice,
        label: close.reason === 'TAKE_PROFIT' ? 'TP' : 'SL',
        tooltip: [
          `<b>Exit</b> at slot ${formatSlot(close.slot)} (${humanizeCode(close.reason)})`,
          `Execution price <b>${formatDecimal(exitPrice)}</b>${unit}`,
          `Fees ${close.feeBps} bps · impact ${signedBps(close.priceImpactBps)}`,
          `Net proceeds ${formatDecimal(values.netProceeds(s))}${unit}`,
          `Realized P&L <b>${formatDecimal(values.realizedPnl(s))}</b>${unit}`,
          ...noteLine(values.exitNote(s)),
        ].join('<br/>'),
      });
    }
  }
  return {
    sourceBuys: sourceSlots.map((slot) => ({ slot, label: 'Source buy' })),
    entries,
    exits,
    rejections,
    levels,
  };
}

function noteLine(note: string | null): string[] {
  return note ? [note] : [];
}

function markerSeries(name: string, markers: PriceMarker[], color: string, rotate = 0): SeriesOption {
  return {
    type: 'scatter',
    name,
    symbol: 'triangle',
    symbolRotate: rotate,
    symbolSize: 14,
    itemStyle: { color, borderColor: terminal.ground, borderWidth: 1 },
    z: 5,
    label: {
      show: true,
      position: rotate ? 'bottom' : 'top',
      formatter: (params: unknown) => (params as { data?: { marker?: string } }).data?.marker ?? '',
      fontWeight: 'bold',
      color,
    },
    data: markers.map((m) => ({ value: [m.slot, toChartNumber(m.price)], marker: m.label, tip: m.tooltip })),
  };
}

/**
 * Price vs slot for one pair's observations, with the scenario markers. The x-axis is padded to
 * include every marker, because ECharts does not widen the axis for mark lines.
 */
export function timelineOption(
  observations: Observation[],
  scenarios: Scenario[],
  values: TimelineValues = quoteValues(''),
): EChartsOption {
  const markers = timelineMarkers(scenarios, values);
  const plotted = observations.flatMap((o) => {
    const price = values.observationPrice(o);
    return price == null ? [] : [{ ...o, price }];
  });
  const slots = [
    ...plotted.map((o) => o.slot),
    ...markers.sourceBuys.map((m) => m.slot),
    ...markers.entries.map((m) => m.slot),
    ...markers.exits.map((m) => m.slot),
    ...markers.rejections.map((m) => m.slot),
  ];
  const min = slots.length ? Math.min(...slots) : 0;
  const max = slots.length ? Math.max(...slots) : 1;
  const pad = Math.max(1, Math.round((max - min) * 0.04));
  const axes = slotAxes(values.unit ? `Price (${values.unit})` : 'Price');

  const verticalLines: SeriesOption = {
    type: 'line',
    name: 'Rejected target',
    data: [],
    itemStyle: { color: REJECTED_COLOR },
    markLine: {
      symbol: 'none',
      silent: false,
      data: [
        ...markers.sourceBuys.map((m) => ({
          xAxis: m.slot,
          name: m.label,
          lineStyle: { color: SOURCE_COLOR, type: 'solid' as const, width: 2 },
          label: { formatter: m.label, color: SOURCE_COLOR, position: 'insideEndTop' as const },
        })),
        ...markers.rejections.map((m) => ({
          xAxis: m.slot,
          name: m.label,
          lineStyle: { color: REJECTED_COLOR, type: 'dashed' as const, width: 1.5 },
          label: { formatter: m.label, color: REJECTED_COLOR, position: 'insideEndBottom' as const },
        })),
      ],
    },
  };

  // Levels are plain two-point lines across the slot range, not mark lines: ECharts drops horizontal
  // mark lines at memecoin-sized prices (around 1e-7), and real lines also widen the price axis to
  // keep take profit and stop loss in view.
  const levelLines: SeriesOption[] = markers.levels.flatMap((m) => {
    const price = toChartNumber(m.price);
    if (price == null) return [];
    return [
      {
        type: 'line',
        name: LEVELS_SERIES,
        data: [
          [min - pad, price],
          [max + pad, price],
        ],
        showSymbol: false,
        silent: true,
        lineStyle: { color: m.color, type: 'dotted', width: 1.5 },
        itemStyle: { color: LEVEL_COLORS.reference },
        endLabel: { show: true, formatter: `${m.label} ${compactDecimal(m.price)}`, color: m.color, fontFamily: FONT_MONO, fontSize: 11 },
        tooltip: { show: false },
        emphasis: { disabled: true },
        z: 0,
      },
    ];
  });

  return {
    // Room on the right for the level labels at the end of each level line.
    grid: { left: 12, right: levelLines.length ? 150 : 32, top: 48, bottom: 64, containLabel: true },
    legend: { top: 0 },
    tooltip: { trigger: 'item', formatter: itemTooltip },
    ...axes,
    xAxis: { ...(axes.xAxis as object), min: min - pad, max: max + pad },
    series: [
      ...observationSeries(plotted, values.observationNote),
      markerSeries('Entry fills', markers.entries, ENTRY_COLOR),
      markerSeries('Exits', markers.exits, EXIT_COLOR, 180),
      verticalLines,
      ...levelLines,
    ],
  } as EChartsOption;
}

/** The distinct pairs traded in a run, in scenario order. */
export function scenarioPairs(scenarios: Scenario[]): string[] {
  return [...new Set(scenarios.map((s) => pairKey(s.pair)))];
}
