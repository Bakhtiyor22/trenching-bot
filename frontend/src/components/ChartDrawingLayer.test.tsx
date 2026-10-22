import { act, fireEvent, screen } from '@testing-library/react';
import type { ECharts } from 'echarts';
import { useState } from 'react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import type { ChartDrawing, DrawingTool } from '../charts/chartDrawings';
import { renderPage } from '../test/render';
import { ChartDrawingLayer } from './ChartDrawingLayer';

const drawing: ChartDrawing = {
  id: 'line',
  kind: 'trend',
  metric: 'mcap',
  a: { time: 100, value: 100 },
  b: { time: 200, value: 200 },
};
function chartInstance() {
  const handlers = new Map<string, () => void>();
  let scale = 1;
  const chart = {
    convertToPixel: vi.fn((_finder, point: number[]) => point.map((value) => value * scale)),
    convertFromPixel: vi.fn((_finder, point: number[]) => point.map((value) => value / scale)),
    containPixel: vi.fn(() => true),
    isDisposed: () => false,
    getDom: () => document.createElement('div'),
    getModel: () => ({
      getComponent: () => ({
        coordinateSystem: { getRect: () => ({ x: 0, y: 0, width: 500, height: 400 }) },
      }),
    }),
    on: vi.fn((name: string, callback: () => void) => {
      handlers.set(name, callback);
    }),
    off: vi.fn(),
  } as unknown as ECharts;
  return {
    chart,
    handlers,
    zoom: () => {
      scale = 2;
      handlers.get('datazoom')?.();
    },
  };
}

function Harness({
  chart,
  initial = [drawing],
  initialTool = 'pointer',
}: {
  chart: ECharts;
  initial?: ChartDrawing[];
  initialTool?: DrawingTool;
}) {
  const [drawings, setDrawings] = useState(initial);
  const [tool, setTool] = useState<DrawingTool>(initialTool);
  return (
    <div>
      <span data-testid="tool">{tool}</span>
      <output data-testid="data">{JSON.stringify(drawings)}</output>
      <ChartDrawingLayer
        chart={chart}
        drawings={drawings}
        onChange={setDrawings}
        tool={tool}
        onToolChange={setTool}
        metric="mcap"
        points={[{ time: 300, value: 300 }]}
      />
    </div>
  );
}
beforeAll(() => {
  if (!window.PointerEvent)
    Object.defineProperty(window, 'PointerEvent', { value: MouseEvent, configurable: true });
});

describe('ChartDrawingLayer', () => {
  it('selects a drawing, moves an endpoint and removes it with Delete', () => {
    const { chart } = chartInstance();
    renderPage(<Harness chart={chart} />);
    const body = screen.getByRole('button', { name: 'Trend line drawing' });
    fireEvent.pointerDown(body, { button: 0, clientX: 150, clientY: 150 });
    fireEvent.pointerUp(screen.getByLabelText('Chart drawing tools'), {
      clientX: 150,
      clientY: 150,
    });
    const handle = screen.getByRole('button', { name: 'Move drawing end' });
    fireEvent.pointerDown(handle, { button: 0, clientX: 200, clientY: 200 });
    fireEvent.pointerMove(screen.getByLabelText('Chart drawing tools'), {
      clientX: 296,
      clientY: 298,
    });
    fireEvent.pointerUp(screen.getByLabelText('Chart drawing tools'), {
      clientX: 296,
      clientY: 298,
    });
    expect(JSON.parse(screen.getByTestId('data').textContent ?? '[]')[0].b).toEqual({
      time: 300,
      value: 300,
    });
    expect(screen.getByRole('button', { name: 'Delete selected drawing' })).toBeVisible();
    fireEvent.keyDown(window, { key: 'Delete' });
    expect(screen.queryByRole('button', { name: 'Trend line drawing' })).not.toBeInTheDocument();
  });
  it('creates a drag drawing, supports Alt free placement and cancels with Escape', () => {
    const { chart } = chartInstance();
    renderPage(<Harness chart={chart} initial={[]} initialTool="measure" />);
    const surface = screen.getByLabelText('Chart drawing tools');
    fireEvent.pointerDown(surface, { button: 0, clientX: 100, clientY: 100 });
    fireEvent.pointerMove(surface, { clientX: 296, clientY: 298, altKey: true });
    expect(screen.getByLabelText('Drawing preview')).toHaveTextContent('+198.00%');
    fireEvent.pointerUp(surface, { clientX: 296, clientY: 298, altKey: true });
    expect(JSON.parse(screen.getByTestId('data').textContent ?? '[]')[0].b).toEqual({
      time: 296,
      value: 298,
    });
    fireEvent.pointerDown(surface, { button: 0, clientX: 50, clientY: 50 });
    fireEvent.pointerUp(surface, { clientX: 50, clientY: 50 });
    expect(screen.getByLabelText('Drawing preview')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByLabelText('Drawing preview')).not.toBeInTheDocument();
    expect(screen.getByTestId('tool')).toHaveTextContent('pointer');
  });
  it('keeps data coordinates while re-projecting after zoom and cleans listeners', () => {
    const { chart, zoom } = chartInstance();
    const { unmount } = renderPage(<Harness chart={chart} />);
    expect(screen.getByTestId('drawing-line').querySelector('line')).toHaveAttribute('x2', '200');
    act(zoom);
    expect(screen.getByTestId('drawing-line').querySelector('line')).toHaveAttribute('x2', '400');
    expect(JSON.parse(screen.getByTestId('data').textContent ?? '[]')[0]).toEqual(drawing);
    unmount();
    expect(chart.off).toHaveBeenCalledWith('datazoom', expect.any(Function));
  });
  it('places endpoints with two clicks and moves the entire drawing by the same delta', () => {
    const { chart } = chartInstance();
    const changed = vi.fn();
    const toolChange = vi.fn();
    const props = {
      chart,
      drawings: [] as ChartDrawing[],
      onChange: changed,
      tool: 'trend' as DrawingTool,
      onToolChange: toolChange,
      metric: 'mcap' as const,
      points: [],
    };
    const { rerender } = renderPage(<ChartDrawingLayer {...props} />);
    const surface = screen.getByLabelText('Chart drawing tools');
    fireEvent.pointerDown(surface, { button: 0, clientX: 100, clientY: 100 });
    fireEvent.pointerUp(surface, { clientX: 100, clientY: 100 });
    fireEvent.pointerMove(surface, { clientX: 200, clientY: 200 });
    fireEvent.pointerDown(surface, { button: 0, clientX: 200, clientY: 200 });
    expect(changed).toHaveBeenLastCalledWith([
      expect.objectContaining({ a: drawing.a, b: drawing.b }),
    ]);
    rerender(<ChartDrawingLayer {...props} tool="pointer" drawings={[drawing]} />);
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Trend line drawing' }), {
      button: 0,
      clientX: 150,
      clientY: 150,
    });
    fireEvent.pointerMove(screen.getByLabelText('Chart drawing tools'), {
      clientX: 170,
      clientY: 160,
      altKey: true,
    });
    expect(changed).toHaveBeenLastCalledWith([
      { ...drawing, a: { time: 120, value: 110 }, b: { time: 220, value: 210 } },
    ]);
  });
});
