export interface TimeRange {
  from: number;
  to: number;
}

export function clampRange(range: TimeRange, extent: TimeRange): TimeRange {
  const span = Math.min(Math.max(1000, range.to - range.from), extent.to - extent.from);
  const from = Math.max(extent.from, Math.min(range.from, extent.to - span));
  return { from, to: from + span };
}

export function zoomRange(range: TimeRange, factor: number, extent: TimeRange): TimeRange {
  const centre = (range.from + range.to) / 2;
  const half = Math.max(500, ((range.to - range.from) * factor) / 2);
  return clampRange({ from: centre - half, to: centre + half }, extent);
}

export function panRange(range: TimeRange, fraction: number, extent: TimeRange): TimeRange {
  const shift = (range.to - range.from) * fraction;
  return clampRange({ from: range.from + shift, to: range.to + shift }, extent);
}

export function tradeRange(firstBuy: number, end: number, extent: TimeRange): TimeRange {
  return clampRange({ from: firstBuy - 10_000, to: end + 30_000 }, extent);
}
