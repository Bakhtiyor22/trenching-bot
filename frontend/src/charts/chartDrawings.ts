import { useCallback, useSyncExternalStore, type SetStateAction } from 'react';
import { marketChange, marketValue } from './marketSummary';

export type DrawingTool = 'pointer' | 'trend' | 'measure';
export interface DrawingPoint {
  time: number;
  value: number;
}
export interface ChartDrawing {
  id: string;
  kind: 'trend' | 'measure';
  metric: 'mcap' | 'price';
  a: DrawingPoint;
  b: DrawingPoint;
}
export interface DrawingPixel {
  x: number;
  y: number;
}
export type DrawingProjector = (point: DrawingPoint) => DrawingPixel | null;

const STORAGE_PREFIX = 'trenching.chart.drawings.v1';
const SNAP_DISTANCE = 8;
const MAX_DRAWINGS = 1000;

export function drawingStorageKey(kind: string, id: string, pair: string): string {
  return `${STORAGE_PREFIX}:${kind}:${id}:${pair}`;
}

function finitePoint(value: unknown): value is DrawingPoint {
  if (value === null || typeof value !== 'object') return false;
  const point = value as Partial<DrawingPoint>;
  return Number.isFinite(point.time) && Number.isFinite(point.value);
}

function validDrawing(value: unknown): value is ChartDrawing {
  if (value === null || typeof value !== 'object') return false;
  const drawing = value as Partial<ChartDrawing>;
  return (
    typeof drawing.id === 'string' &&
    drawing.id.length > 0 &&
    (drawing.kind === 'trend' || drawing.kind === 'measure') &&
    (drawing.metric === 'mcap' || drawing.metric === 'price') &&
    finitePoint(drawing.a) &&
    finitePoint(drawing.b)
  );
}

interface DrawingSnapshot {
  drawings: ChartDrawing[];
  storageError: boolean;
}
const snapshots = new Map<string, DrawingSnapshot>();
const listeners = new Map<string, Set<() => void>>();

function snapshot(key: string): DrawingSnapshot {
  const cached = snapshots.get(key);
  if (cached) return cached;
  let initial: DrawingSnapshot;
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(key) ?? '[]');
    const drawings = Array.isArray(stored)
      ? stored.filter(validDrawing).slice(0, MAX_DRAWINGS)
      : [];
    initial = {
      drawings: [...new Map(drawings.map((drawing) => [drawing.id, drawing])).values()],
      storageError: false,
    };
  } catch {
    initial = { drawings: [], storageError: true };
  }
  snapshots.set(key, initial);
  return initial;
}

/** Storage failures keep the drawings usable for the current browser session. */
export function useChartDrawings(kind: string, id: string, pair: string) {
  const key = drawingStorageKey(kind, id, pair);
  const subscribe = useCallback(
    (listener: () => void) => {
      const subscribers = listeners.get(key) ?? new Set<() => void>();
      subscribers.add(listener);
      listeners.set(key, subscribers);
      return () => {
        subscribers.delete(listener);
      };
    },
    [key],
  );
  const getSnapshot = useCallback(() => snapshot(key), [key]);
  const state = useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
  const setDrawings = useCallback(
    (update: SetStateAction<ChartDrawing[]>) => {
      const previous = snapshot(key);
      const drawings = (typeof update === 'function' ? update(previous.drawings) : update)
        .filter(validDrawing)
        .slice(0, MAX_DRAWINGS);
      let storageError = false;
      try {
        localStorage.setItem(key, JSON.stringify(drawings));
      } catch {
        storageError = true;
      }
      snapshots.set(key, { drawings, storageError: previous.storageError || storageError });
      listeners.get(key)?.forEach((listener) => listener());
    },
    [key],
  );
  return { ...state, setDrawings };
}

/** Drawings start as plotted numeric coordinates; convert them back to plain decimal text. */
export function drawingDecimal(value: number): string {
  return value.toLocaleString('en-US', { useGrouping: false, maximumSignificantDigits: 15 });
}

export function drawingLabel(drawing: Pick<ChartDrawing, 'a' | 'b' | 'metric'>): string {
  const change = marketChange(drawingDecimal(drawing.a.value), drawingDecimal(drawing.b.value));
  const sign = change.up ? '+' : '-';
  const absolute = marketValue(change.absolute.replace(/^-/, ''), drawing.metric);
  const duration = (drawing.b.time - drawing.a.time) / 1000;
  const elapsed = `${duration < 0 ? '-' : '+'}${Math.abs(duration).toFixed(1)}s`;
  return `${change.percent === null ? '—' : `${sign}${change.percent}%`} · ${sign}${absolute} · ${elapsed}`;
}

export function snapDrawingPoint(
  point: DrawingPoint,
  points: { time: number; value: number | null }[],
  project: DrawingProjector,
  altKey = false,
): DrawingPoint {
  if (altKey) return point;
  const cursor = project(point);
  if (!cursor) return point;
  let distance = SNAP_DISTANCE;
  let nearest = point;
  for (const candidate of points) {
    if (candidate.value === null) continue;
    const value = { time: candidate.time, value: candidate.value };
    const pixel = project(value);
    if (!pixel) continue;
    const nextDistance = Math.hypot(pixel.x - cursor.x, pixel.y - cursor.y);
    if (nextDistance <= distance) {
      nearest = value;
      distance = nextDistance;
    }
  }
  return nearest;
}
