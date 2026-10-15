import { createElement, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { server } from '../mocks/server';
import { PAIR, SAMPLE_RUN_ID, WALLET, REAL_RUN_ID, V4_DATASET_ID } from '../mocks/fixtures';
import { api, pairKey } from './endpoints';
import { createQueryClient, useDatasetChart, useRunChart } from './queries';
import type { ChartResponse } from './types';

const chart: ChartResponse = {
  bucketSeconds: 1,
  range: { from: '2026-10-01T00:00:00Z', to: '2026-10-01T00:01:00Z' },
  trackedWallet: WALLET,
  valuationBasis: { label: 'Mcap', supply: '1000000000', supplySource: 'recorded', quoteCurrency: 'USDT', rateSource: 'recorded', rateGranularity: '1m', available: true, unavailableReason: null },
  candles: [],
  markers: { available: true, unavailableReason: null, mode: 'CLUSTERED', total: 3, items: [], nextCursor: 'opaque / token', clusters: [{ bucketStart: '2026-10-01T00:00:00Z', count: 1, buyCount: 1, sellCount: 0, firstSlot: 1, lastSlot: 1, firstTime: '2026-10-01T00:00:00Z', lastTime: '2026-10-01T00:00:00Z' }] },
  paperFills: [],
};

describe('chart API', () => {
  it('serves v3 unavailability and v4 bounded, wallet-filtered pages from mocks', async () => {
    const v3 = await api.runChart(SAMPLE_RUN_ID, { pair: pairKey(PAIR) });
    expect(v3.markers.unavailableReason).toBe('NOT_RECORDED_BEFORE_SCHEMA_V4');
    const first = await api.runChart(REAL_RUN_ID, { markerLimit: 1, wallet: [WALLET] });
    expect(first.markers.items).toHaveLength(1);
    expect(first.markers.nextCursor).not.toBeNull();
    const cursor = first.markers.nextCursor ?? '';
    const next = await api.runChart(REAL_RUN_ID, { markerLimit: 1, wallet: [WALLET], cursor });
    expect(next.markers.items[0]?.observationId).not.toBe(first.markers.items[0]?.observationId);
    const dataset = await api.datasetChart(V4_DATASET_ID, { pair: pairKey(PAIR), from: '2026-10-01T00:00:01Z', to: '2026-10-01T00:00:03Z' });
    expect(dataset.paperFills).toEqual([]);
    expect(dataset.candles.every((candle) => Date.parse(candle.bucketStart) >= Date.parse('2026-10-01T00:00:01Z'))).toBe(true);
    const clustered = await api.runChart(REAL_RUN_ID, { markerLimit: 1 });
    expect(clustered.markers.mode).toBe('CLUSTERED');
    expect(clustered.markers.clusters.length).toBeGreaterThan(0);
    const secondPage = await api.runChart(REAL_RUN_ID, { markerLimit: 1, cursor: clustered.markers.nextCursor ?? '' });
    expect(secondPage.markers.clusters).toEqual([]);
    const coarse = await api.datasetChart(V4_DATASET_ID, { pair: pairKey(PAIR), maxBuckets: 1 });
    expect(coarse.candles).toHaveLength(1);
  });

  it('returns chart errors through the existing API envelope', async () => {
    await expect(api.datasetChart('missing', { pair: pairKey(PAIR) })).rejects.toMatchObject({ status: 404, code: 'DATASET_NOT_FOUND' });
    await expect(api.datasetChart(V4_DATASET_ID, {})).rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST' });
    await expect(api.runChart(REAL_RUN_ID, { wallet: ['invalid'] })).rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST' });
    await expect(api.runChart(REAL_RUN_ID, { cursor: 'invalid' })).rejects.toMatchObject({ status: 400, code: 'INVALID_REQUEST' });
  });

  it('sends repeatable wallet parameters and keeps decimal values intact', async () => {
    let received: URL | undefined;
    server.use(http.get('/api/runs/:id/chart', ({ request }) => {
      received = new URL(request.url);
      return HttpResponse.json(chart);
    }));
    const response = await api.runChart(SAMPLE_RUN_ID, { pair: pairKey(PAIR), wallet: [WALLET, PAIR.base], markerLimit: 1, from: chart.range.from });
    expect(received?.searchParams.getAll('wallet')).toEqual([WALLET, PAIR.base]);
    expect(received?.searchParams.get('pair')).toBe(pairKey(PAIR));
    expect(received?.searchParams.get('markerLimit')).toBe('1');
    expect(response.valuationBasis.supply).toBe('1000000000');
  });

  it('follows opaque cursors and retains the first page clusters', async () => {
    const marker = { kind: 'OBSERVED' as const, observationId: 'first', trader: WALLET, isTrackedWallet: true, side: 'BUY' as const, solLamports: '1000000000', tokenRaw: '1000000', price: '0.0000001', mcap: '20000', slot: 1, time: chart.range.from, signature: null, source: 'recorded' };
    const cursors: (string | null)[] = [];
    server.use(http.get('/api/runs/:id/chart', ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      cursors.push(cursor);
      return HttpResponse.json({ ...chart, markers: { ...chart.markers, items: [{ ...marker, observationId: cursor ? 'second' : 'first' }], clusters: cursor ? [] : chart.markers.clusters, nextCursor: cursor ? null : chart.markers.nextCursor } });
    }));
    const client = createQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
    const result = renderHook(() => useRunChart(SAMPLE_RUN_ID, { pair: pairKey(PAIR) }), { wrapper });
    await waitFor(() => expect(result.result.current.isSuccess).toBe(true));
    expect(cursors).toEqual([null, 'opaque / token']);
    expect(result.result.current.data?.markers.items.map((item) => item.observationId)).toEqual(['first', 'second']);
    expect(result.result.current.data?.markers.clusters).toEqual(chart.markers.clusters);
    expect(result.result.current.data?.markers.nextCursor).toBeNull();
  });

  it('cancels old cursor requests when the dataset chart range changes', async () => {
    let cursorSignal: AbortSignal | null | undefined;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const params = new URL(String(input)).searchParams;
      if (params.has('cursor')) {
        cursorSignal = init?.signal;
        return new Promise<Response>((_resolve, reject) => {
          cursorSignal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
        });
      }
      const nextCursor = params.has('from') ? null : chart.markers.nextCursor;
      return Response.json({ ...chart, range: { ...chart.range, from: params.get('from') ?? chart.range.from }, markers: { ...chart.markers, nextCursor } });
    });
    const client = createQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
    const result = renderHook(({ from }: { from?: string }) => useDatasetChart(V4_DATASET_ID, { pair: pairKey(PAIR), from }), { wrapper, initialProps: {} as { from?: string } });
    try {
      await waitFor(() => expect(cursorSignal).toBeDefined());
      expect(cursorSignal?.aborted).toBe(false);
      result.rerender({ from: '2026-10-01T00:00:01Z' });
      await waitFor(() => expect(cursorSignal?.aborted).toBe(true));
      await waitFor(() => expect(result.result.current.isSuccess).toBe(true));
      expect(result.result.current.data?.range.from).toBe('2026-10-01T00:00:01Z');
    } finally {
      result.unmount();
      client.clear();
      fetchSpy.mockRestore();
    }
  });
});
