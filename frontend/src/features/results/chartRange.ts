export interface TimeRange {
  from: number;
  to: number;
}

export function clampRange(range: TimeRange, extent: TimeRange): TimeRange {
  const span = Math.min(
    Math.max(2000, range.to - range.from),
    Math.max(0, extent.to - extent.from),
  );
  const from = Math.max(extent.from, Math.min(range.from, extent.to - span));
  return { from, to: from + span };
}

export function zoomRange(
  range: TimeRange,
  factor: number,
  extent: TimeRange,
  anchor = (range.from + range.to) / 2,
): TimeRange {
  const span = Math.max(2000, (range.to - range.from) * factor);
  const position = Math.max(0, Math.min(1, (anchor - range.from) / (range.to - range.from || 1)));
  const from = anchor - span * position;
  return clampRange({ from, to: from + span }, extent);
}

export function panRange(range: TimeRange, fraction: number, extent: TimeRange): TimeRange {
  const shift = (range.to - range.from) * fraction;
  return clampRange({ from: range.from + shift, to: range.to + shift }, extent);
}

export function tradeRange(firstBuy: number, end: number, extent: TimeRange): TimeRange {
  return clampRange({ from: firstBuy - 10_000, to: end + 30_000 }, extent);
}
