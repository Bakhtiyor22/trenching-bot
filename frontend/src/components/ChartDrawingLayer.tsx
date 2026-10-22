import type { ECharts } from 'echarts';
import { useEffect, useId, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import {
  drawingLabel,
  snapDrawingPoint,
  type ChartDrawing,
  type DrawingPixel,
  type DrawingPoint,
  type DrawingTool,
} from '../charts/chartDrawings';
import { terminal } from '../theme';
import { chartContainsPoint, chartGeometryReady, chartGridRect } from '../charts/chartGeometry';

export interface ChartDrawingLayerProps {
  chart: ECharts | null;
  drawings: ChartDrawing[];
  onChange: (drawings: ChartDrawing[]) => void;
  tool: DrawingTool;
  onToolChange: (tool: DrawingTool) => void;
  metric: 'mcap' | 'price';
  points: { time: number; value: number | null }[];
}
interface PlotRect {
  x: number;
  y: number;
  width: number;
  height: number;
}
interface ProjectedDrawing {
  drawing: ChartDrawing;
  a: DrawingPixel;
  b: DrawingPixel;
}
interface Projection {
  rect: PlotRect;
  drawings: ProjectedDrawing[];
}
interface DragState {
  drawing: ChartDrawing;
  part: 'a' | 'b' | 'body';
  start: DrawingPoint;
}

function gridRect(chart: ECharts): PlotRect {
  const rect = chartGridRect(chart);
  if (rect) return rect;
  const dom = chart.getDom();
  return { x: 0, y: 0, width: dom.clientWidth, height: dom.clientHeight };
}

function editableTarget(target: EventTarget | null) {
  return (
    target instanceof HTMLElement &&
    Boolean(target.closest('input, textarea, select, [contenteditable="true"]'))
  );
}

function DrawingShape({
  item: { drawing, a, b },
  rect,
  active,
  creating,
  interactive,
  onSelect,
  onDrag,
}: {
  item: ProjectedDrawing;
  rect: PlotRect | undefined;
  active: boolean;
  creating: boolean;
  interactive: boolean;
  onSelect: () => void;
  onDrag: (event: ReactPointerEvent, drawing: ChartDrawing, part: DragState['part']) => void;
}) {
  const color = drawing.b.value >= drawing.a.value ? terminal.gain : terminal.loss;
  const text = drawingLabel(drawing);
  const lines = text.length * 6.5 + 14 > (rect?.width ?? 400) - 8 ? text.split(' · ') : [text];
  const textWidth = Math.min(
    Math.max(...lines.map((line) => line.length)) * 6.5 + 14,
    (rect?.width ?? 400) - 8,
  );
  const textHeight = lines.length * 14 + 6;
  const x = Math.max(
    (rect?.x ?? 0) + 4,
    Math.min(
      drawing.kind === 'measure' ? (a.x + b.x - textWidth) / 2 : b.x + 8,
      (rect?.x ?? 0) + (rect?.width ?? 400) - textWidth - 4,
    ),
  );
  const y = Math.max(
    (rect?.y ?? 0) + 17,
    Math.min(
      drawing.kind === 'measure' ? (a.y + b.y) / 2 : b.y - 10,
      (rect?.y ?? 0) + (rect?.height ?? 300) - textHeight + 10,
    ),
  );
  return (
    <g
      data-testid={`drawing-${drawing.id}`}
      role={creating ? undefined : 'button'}
      tabIndex={creating ? undefined : 0}
      aria-label={
        creating
          ? 'Drawing preview'
          : `${drawing.kind === 'trend' ? 'Trend line' : 'Measure'} drawing`
      }
      aria-pressed={creating ? undefined : active}
      onFocus={onSelect}
      onPointerDown={(event) => {
        onDrag(event, drawing, 'body');
      }}
      style={{ pointerEvents: interactive && !creating ? 'auto' : 'none', cursor: 'move' }}
    >
      {drawing.kind === 'measure' ? (
        <rect
          x={Math.min(a.x, b.x)}
          y={Math.min(a.y, b.y)}
          width={Math.max(1, Math.abs(b.x - a.x))}
          height={Math.max(1, Math.abs(b.y - a.y))}
          fill={color}
          fillOpacity={0.12}
          stroke={color}
          strokeWidth={active ? 2 : 1}
        />
      ) : (
        <>
          <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="transparent" strokeWidth={12} />
          <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke={color} strokeWidth={active ? 2 : 1.5} />
        </>
      )}
      <rect
        x={x}
        y={y - 14}
        width={textWidth}
        height={textHeight}
        rx={3}
        fill={terminal.panel}
        fillOpacity={0.95}
      />
      <text
        x={x + 7}
        y={y}
        fill={color}
        fontSize={11}
        fontFamily="monospace"
        style={{ userSelect: 'none' }}
      >
        {lines.length === 1
          ? text
          : lines.map((line, index) => (
              <tspan key={index} x={x + 7} dy={index === 0 ? 0 : 14}>
                {line}
                {index < lines.length - 1 ? ' · ' : ''}
              </tspan>
            ))}
      </text>
      {active &&
        (['a', 'b'] as const).map((part) => {
          const point = part === 'a' ? a : b;
          return (
            <g
              key={part}
              role="button"
              tabIndex={0}
              aria-label={`Move drawing ${part === 'a' ? 'start' : 'end'}`}
              onPointerDown={(event) => {
                onDrag(event, drawing, part);
              }}
              style={{ cursor: 'grab' }}
            >
              <circle cx={point.x} cy={point.y} r={10} fill="transparent" />
              <circle
                cx={point.x}
                cy={point.y}
                r={4}
                fill={terminal.panel}
                stroke={color}
                strokeWidth={2}
              />
            </g>
          );
        })}
    </g>
  );
}

export function ChartDrawingLayer({
  chart,
  drawings,
  onChange,
  tool,
  onToolChange,
  metric,
  points,
}: ChartDrawingLayerProps) {
  const clipId = useId().replace(/:/g, '');
  const root = useRef<HTMLDivElement>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [creation, setCreation] = useState<{
    tool: DrawingTool;
    metric: 'mcap' | 'price';
    drawing: ChartDrawing | null;
  }>({ tool, metric, drawing: null });
  if (creation.tool !== tool || creation.metric !== metric)
    setCreation({ tool, metric, drawing: null });
  const draft = creation.tool === tool && creation.metric === metric ? creation.drawing : null;
  function setDraft(drawing: ChartDrawing | null) {
    setCreation({ tool, metric, drawing });
  }
  const [projection, setProjection] = useState<Projection | null>(null);
  const signature = useRef('');
  const drag = useRef<DragState | null>(null);
  const press = useRef<DrawingPixel | null>(null);
  const state = useRef({ drawings, selected, draft, onChange, onToolChange });
  useEffect(() => {
    state.current = { drawings, selected, draft, onChange, onToolChange };
  }, [drawings, selected, draft, onChange, onToolChange]);

  function project(point: DrawingPoint): DrawingPixel | null {
    if (!chart || chart.isDisposed()) return null;
    const pixel: unknown = chart.convertToPixel({ xAxisIndex: 0, yAxisIndex: 0 }, [
      point.time,
      point.value,
    ]);
    return Array.isArray(pixel) && pixel.every(Number.isFinite)
      ? { x: pixel[0] as number, y: pixel[1] as number }
      : null;
  }

  useEffect(() => {
    function update() {
      if (!chart || chart.isDisposed()) return;
      if (!chartGeometryReady(chart)) return;
      const next: Projection = { rect: gridRect(chart), drawings: [] };
      const visible = [...drawings, ...(draft ? [draft] : [])].filter(
        (drawing) => drawing.metric === metric,
      );
      for (const drawing of visible) {
        const values = [drawing.a, drawing.b].map(
          (point) =>
            chart.convertToPixel({ xAxisIndex: 0, yAxisIndex: 0 }, [
              point.time,
              point.value,
            ]) as unknown,
        );
        const a = values[0];
        const b = values[1];
        if (
          Array.isArray(a) &&
          a.every(Number.isFinite) &&
          Array.isArray(b) &&
          b.every(Number.isFinite)
        )
          next.drawings.push({
            drawing,
            a: { x: a[0] as number, y: a[1] as number },
            b: { x: b[0] as number, y: b[1] as number },
          });
      }
      const serialized = JSON.stringify(next);
      if (serialized !== signature.current) {
        signature.current = serialized;
        setProjection(next);
      }
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
  }, [chart, drawings, draft, metric]);

  useEffect(() => {
    function keyDown(event: KeyboardEvent) {
      if (editableTarget(event.target)) return;
      if (event.key === 'Escape') {
        setCreation((current) => ({ ...current, drawing: null }));
        drag.current = null;
        press.current = null;
        state.current.onToolChange('pointer');
      } else if (
        (event.key === 'Delete' || event.key === 'Backspace') &&
        state.current.selected &&
        root.current?.parentElement?.contains(document.activeElement)
      ) {
        event.preventDefault();
        state.current.onChange(
          state.current.drawings.filter((drawing) => drawing.id !== state.current.selected),
        );
        setSelected(null);
      }
    }
    window.addEventListener('keydown', keyDown);
    return () => {
      window.removeEventListener('keydown', keyDown);
    };
  }, []);

  function coordinates(event: ReactPointerEvent, snap = true): DrawingPoint | null {
    if (
      !chart ||
      chart.isDisposed() ||
      typeof chart.convertFromPixel !== 'function' ||
      !root.current
    )
      return null;
    const rect = root.current.getBoundingClientRect();
    const pixel = [event.clientX - rect.left, event.clientY - rect.top];
    const value: unknown = chart.convertFromPixel({ xAxisIndex: 0, yAxisIndex: 0 }, pixel);
    if (!Array.isArray(value) || !value.every(Number.isFinite)) return null;
    const point = { time: value[0] as number, value: value[1] as number };
    return snap ? snapDrawingPoint(point, points, project, event.altKey) : point;
  }

  function capture(event: ReactPointerEvent) {
    root.current?.focus();
    root.current?.setPointerCapture?.(event.pointerId);
  }

  function startDrawing(event: ReactPointerEvent) {
    if (tool === 'pointer' || event.button !== 0 || !chart || !root.current) return;
    const rect = root.current.getBoundingClientRect();
    if (!chartContainsPoint(chart, [event.clientX - rect.left, event.clientY - rect.top])) return;
    const point = coordinates(event);
    if (!point) return;
    event.preventDefault();
    event.stopPropagation();
    capture(event);
    if (draft) {
      const drawing = { ...draft, b: point };
      onChange([...drawings, drawing]);
      setSelected(drawing.id);
      setDraft(null);
      press.current = null;
    } else {
      setDraft({ id: crypto.randomUUID(), kind: tool, metric, a: point, b: point });
      press.current = { x: event.clientX, y: event.clientY };
    }
  }

  function startDrag(event: ReactPointerEvent, drawing: ChartDrawing, part: DragState['part']) {
    if (tool !== 'pointer' || event.button !== 0) return;
    const start = coordinates(event, false);
    if (!start) return;
    event.preventDefault();
    event.stopPropagation();
    capture(event);
    setSelected(drawing.id);
    drag.current = { drawing, part, start };
  }

  function move(event: ReactPointerEvent) {
    const point = coordinates(event);
    if (!point) return;
    if (draft) {
      setDraft({ ...draft, b: point });
      return;
    }
    const moving = drag.current;
    if (!moving) return;
    let updated: ChartDrawing;
    if (moving.part === 'body') {
      const current = coordinates(event, false);
      if (!current) return;
      const a = snapDrawingPoint(
        {
          time: moving.drawing.a.time + current.time - moving.start.time,
          value: moving.drawing.a.value + current.value - moving.start.value,
        },
        points,
        project,
        event.altKey,
      );
      updated = {
        ...moving.drawing,
        a,
        b: {
          time: moving.drawing.b.time + a.time - moving.drawing.a.time,
          value: moving.drawing.b.value + a.value - moving.drawing.a.value,
        },
      };
    } else updated = { ...moving.drawing, [moving.part]: point };
    onChange(drawings.map((drawing) => (drawing.id === updated.id ? updated : drawing)));
  }

  function finish(event: ReactPointerEvent) {
    if (
      draft &&
      press.current &&
      Math.hypot(event.clientX - press.current.x, event.clientY - press.current.y) > 4
    ) {
      const point = coordinates(event);
      if (point) {
        const drawing = { ...draft, b: point };
        onChange([...drawings, drawing]);
        setSelected(drawing.id);
        setDraft(null);
      }
    }
    press.current = null;
    drag.current = null;
    if (root.current?.hasPointerCapture?.(event.pointerId))
      root.current.releasePointerCapture(event.pointerId);
  }

  const selectedProjection = projection?.drawings.find(
    (item) => item.drawing.id === selected && item.drawing.id !== draft?.id,
  );
  const rect = projection?.rect;
  return (
    <div
      ref={root}
      tabIndex={-1}
      aria-label="Chart drawing tools"
      onPointerDown={startDrawing}
      onPointerMove={move}
      onPointerUp={finish}
      onPointerCancel={() => {
        drag.current = null;
        press.current = null;
        setDraft(null);
      }}
      style={{
        position: 'absolute',
        inset: 0,
        zIndex: 5,
        outline: 'none',
        pointerEvents: tool === 'pointer' ? 'none' : 'auto',
        touchAction: tool === 'pointer' ? 'auto' : 'none',
        cursor: tool === 'pointer' ? undefined : 'crosshair',
      }}
    >
      <svg
        width="100%"
        height="100%"
        style={{ overflow: 'hidden', position: 'absolute', inset: 0 }}
        aria-label="Chart drawings"
      >
        {rect && (
          <defs>
            <clipPath id={clipId}>
              <rect x={rect.x} y={rect.y} width={rect.width} height={rect.height} />
            </clipPath>
          </defs>
        )}
        <g clipPath={`url(#${clipId})`}>
          {projection?.drawings.map((item) => (
            <DrawingShape
              key={item.drawing.id}
              item={item}
              rect={rect}
              active={selected === item.drawing.id && tool === 'pointer'}
              creating={draft?.id === item.drawing.id}
              interactive={tool === 'pointer'}
              onSelect={() => {
                setSelected(item.drawing.id);
              }}
              onDrag={startDrag}
            />
          ))}
        </g>
      </svg>
      {selectedProjection && rect && tool === 'pointer' && (
        <button
          type="button"
          aria-label="Delete selected drawing"
          onClick={() => {
            onChange(drawings.filter((drawing) => drawing.id !== selected));
            setSelected(null);
          }}
          style={{
            position: 'absolute',
            left: Math.max(rect.x, Math.min(selectedProjection.b.x + 8, rect.x + rect.width - 65)),
            top: Math.max(rect.y, Math.min(selectedProjection.b.y + 12, rect.y + rect.height - 28)),
            pointerEvents: 'auto',
            background: terminal.panel,
            border: `1px solid ${terminal.line}`,
            borderRadius: 4,
            color: terminal.text,
            padding: '3px 8px',
            fontSize: 11,
          }}
        >
          Delete
        </button>
      )}
    </div>
  );
}
