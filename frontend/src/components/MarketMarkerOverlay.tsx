import type { ECharts } from 'echarts';
import { useEffect, useRef, useState } from 'react';
import { actionKey, groupMarkerPositions, markerLabel, type MarketAction, type MarkerAnchor, type MarkerPosition } from '../charts/marketMarkers';
import { terminal } from '../theme';
import { toChartNumber } from '../lib/decimal';

export interface MarketMarkerOverlayProps {
  actions: MarketAction[];
  view: 'mcap' | 'price';
  chart: ECharts | null;
  selectedKey: string | null;
  onSelect: (action: MarketAction, anchor: MarkerAnchor) => void;
  onStack: (actions: MarketAction[], anchor: MarkerAnchor) => void;
  onPositions?: (positions: Map<string, MarkerAnchor>) => void;
}

export function MarketMarkerOverlay({ actions, view, chart, selectedKey, onSelect, onStack, onPositions }: MarketMarkerOverlayProps) {
  const [positions, setPositions] = useState<MarkerPosition[]>([]);
  const previous = useRef('');
  const onPositionsRef = useRef(onPositions);
  useEffect(() => { onPositionsRef.current = onPositions; }, [onPositions]);
  useEffect(() => {
    function update() {
      const next: MarkerPosition[] = [];
      if (chart && !chart.isDisposed()) {
        for (const action of actions) {
          if (action.kind === 'OBSERVED' && !action.isTrackedWallet) continue;
          const metric = toChartNumber(action[view]);
          if (metric === null) continue;
          const values = [Date.parse(action.time), metric];
          if (!values.every(Number.isFinite)) continue;
          const pixel: unknown = chart.convertToPixel({ xAxisIndex: 0, yAxisIndex: 0 }, values);
          if (!Array.isArray(pixel) || !Number.isFinite(pixel[0]) || !Number.isFinite(pixel[1])) continue;
          if (!chart.containPixel({ gridIndex: 0 }, pixel)) continue;
          const point = { x: pixel[0] as number, y: pixel[1] as number };
          next.push({ action, point, center: { x: point.x, y: point.y + (action.kind === 'PAPER' ? 0 : action.side === 'BUY' ? -26 : 26) } });
        }
      }
      const signature = JSON.stringify(next.map((position) => [position.action, position.point, position.center]));
      if (signature === previous.current) return;
      previous.current = signature;
      setPositions(next);
      const groups = groupMarkerPositions(next);
      onPositionsRef.current?.(new Map(groups.flatMap((group) => group.actions.map((action) => [actionKey(action), group.center] as const))));
    }
    update();
    if (!chart || chart.isDisposed()) return;
    chart.on('finished', update);
    chart.on('datazoom', update);
    window.addEventListener('resize', update);
    const observer = new ResizeObserver(update);
    observer.observe(chart.getDom());
    return () => {
      chart.off('finished', update);
      chart.off('datazoom', update);
      window.removeEventListener('resize', update);
      observer.disconnect();
    };
  }, [actions, chart, view]);

  return <div aria-label="Market action markers" style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}>
    {groupMarkerPositions(positions).map((group) => {
      const action = group.actions[0]!;
      const key = actionKey(action);
      const stacked = group.actions.length > 1;
      const paper = action.kind === 'PAPER';
      const sell = action.side === 'SELL';
      const selected = group.actions.some((item) => actionKey(item) === selectedKey);
      const color = paper ? terminal.info : terminal.accent;
      return <button key={key} id={`market-action-${encodeURIComponent(key)}`} type="button" data-market-action data-market-marker data-market-action-keys={JSON.stringify(group.actions.map(actionKey))} aria-label={stacked ? `${group.actions.length} Decu actions at ${markerLabel(action).split(' at ')[1]}` : markerLabel(action)} aria-pressed={selected} onClick={(event) => { event.stopPropagation(); if (stacked) onStack(group.actions, group.center); else onSelect(action, group.center); }} style={{ position: 'absolute', left: group.center.x - 22, top: group.center.y - 22, width: 44, height: 44, padding: 0, border: 0, borderRadius: '50%', background: 'transparent', cursor: 'pointer', pointerEvents: 'auto', color, display: 'grid', placeItems: 'center' }}>
        {!paper && !stacked && <>
          <span aria-hidden="true" style={{ position: 'absolute', left: 21, top: sell ? -4 : 35, width: 2, height: 12, background: color }} />
          <span aria-hidden="true" style={{ position: 'absolute', left: 18.5, top: sell ? -7.5 : 44.5, width: 7, height: 7, boxSizing: 'border-box', border: `2px solid ${color}`, borderRadius: '50%', background: terminal.panel }} />
        </>}
        {stacked && <span aria-hidden="true" style={{ position: 'absolute', left: 12, top: 6, width: 26, height: 26, border: `2px solid ${color}`, boxSizing: 'border-box', borderRadius: '50%', background: terminal.panel }} />}
        <span aria-hidden="true" data-testid={`market-pin-${key}`} style={{ position: 'relative', display: 'grid', placeItems: 'center', width: paper ? 11 : 26, height: paper ? 11 : 26, boxSizing: 'border-box', borderRadius: paper ? 0 : '50%', border: `2px solid ${color}`, background: paper || (sell && !stacked) ? terminal.panel : color, color: sell && !stacked ? color : terminal.ground, transform: paper ? 'rotate(45deg)' : undefined, fontSize: 12, fontWeight: 700, boxShadow: selected ? `0 0 0 3px ${terminal.panel}, 0 0 0 5px ${terminal.text}` : undefined }}>{paper ? '' : stacked ? group.actions.length : sell ? 'S' : 'B'}</span>
      </button>;
    })}
  </div>;
}
