import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  drawingDecimal,
  drawingLabel,
  drawingStorageKey,
  snapDrawingPoint,
  useChartDrawings,
  type ChartDrawing,
} from './chartDrawings';

const drawing: ChartDrawing = {
  id: 'line',
  kind: 'trend',
  metric: 'mcap',
  a: { time: 1000, value: 10000 },
  b: { time: 5200, value: 11234 },
};
const project = (point: { time: number; value: number }) => ({ x: point.time, y: point.value });

describe('chart drawings', () => {
  it('formats signed changes in both metrics, down moves and a zero start', () => {
    expect(drawingLabel(drawing)).toBe('+12.34% · +$1.2k · +4.2s');
    expect(drawingLabel({ ...drawing, b: { time: 0, value: 8766 } })).toBe(
      '-12.34% · -$1.2k · -1.0s',
    );
    expect(drawingLabel({ ...drawing, a: { time: 1000, value: 0 } })).toBe('— · +$11.2k · +4.2s');
    expect(
      drawingLabel({
        metric: 'price',
        a: { time: 0, value: 0.0000001 },
        b: { time: 4200, value: 0.00000011234 },
      }),
    ).toBe('+12.34% · +0.0₇1234 SOL · +4.2s');
    expect(drawingDecimal(1.23e-9)).toBe('0.00000000123');
  });
  it('snaps to the nearest plotted value within eight pixels and allows Alt free placement', () => {
    const point = { time: 100, value: 100 };
    const points = [
      { time: 104, value: 104 },
      { time: 102, value: 101 },
      { time: 100, value: null },
    ];
    expect(snapDrawingPoint(point, points, project)).toEqual({ time: 102, value: 101 });
    expect(snapDrawingPoint(point, points, project, true)).toBe(point);
    expect(snapDrawingPoint(point, [{ time: 110, value: 100 }], project)).toBe(point);
  });
  it('persists drawings by chart identity and retains them through remounts', () => {
    const key = drawingStorageKey('run', 'storage-roundtrip', 'pair');
    localStorage.removeItem(key);
    const first = renderHook(() => useChartDrawings('run', 'storage-roundtrip', 'pair'));
    act(() => {
      first.result.current.setDrawings([drawing]);
    });
    expect(JSON.parse(localStorage.getItem(key) ?? '[]')).toEqual([drawing]);
    first.unmount();
    const second = renderHook(() => useChartDrawings('run', 'storage-roundtrip', 'pair'));
    expect(second.result.current.drawings).toEqual([drawing]);
    expect(second.result.current.storageError).toBe(false);
    const other = renderHook(() => useChartDrawings('dataset', 'storage-roundtrip', 'pair'));
    expect(other.result.current.drawings).toEqual([]);
    act(() => {
      second.result.current.setDrawings([]);
    });
  });
  it('rejects invalid stored data and continues in memory when storage is unavailable', () => {
    localStorage.setItem(
      drawingStorageKey('run', 'invalid-data', 'pair'),
      JSON.stringify([
        drawing,
        { ...drawing, id: 'bad', a: { time: 'bad', value: 4 } },
        { ...drawing, kind: 'unknown' },
      ]),
    );
    const valid = renderHook(() => useChartDrawings('run', 'invalid-data', 'pair'));
    expect(valid.result.current.drawings).toEqual([drawing]);
    const read = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const unavailable = renderHook(() => useChartDrawings('run', 'read-failure', 'pair'));
    expect(unavailable.result.current.storageError).toBe(true);
    read.mockRestore();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    act(() => {
      unavailable.result.current.setDrawings([drawing]);
    });
    expect(unavailable.result.current.drawings).toEqual([drawing]);
    expect(unavailable.result.current.storageError).toBe(true);
  });
});
