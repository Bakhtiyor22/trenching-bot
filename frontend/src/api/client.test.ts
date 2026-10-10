import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../mocks/server';
import { ApiError, getJson, withQuery } from './client';

describe('api client', () => {
  it('parses the backend error envelope', async () => {
    server.use(
      http.get('/api/thing', () =>
        HttpResponse.json(
          { code: 'CONFIG_INVALID', message: 'bad config', fields: { latencySlots: 'too big' } },
          { status: 400 },
        ),
      ),
    );
    const error = await getJson('/api/thing').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 400, code: 'CONFIG_INVALID', message: 'bad config', fields: { latencySlots: 'too big' } });
    expect((error as ApiError).isClientError).toBe(true);
  });

  it('turns a non-JSON error page into a readable message', async () => {
    server.use(http.get('/api/thing', () => new HttpResponse('<html>404</html>', { status: 404 })));
    const error = (await getJson('/api/thing').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('HTTP_ERROR');
    expect(error.message).toMatch(/Is the console enabled/);
  });

  it('keeps decimal strings as strings', async () => {
    server.use(http.get('/api/thing', () => HttpResponse.json({ price: '0.00000000001234' })));
    const body = await getJson<{ price: string }>('/api/thing');
    expect(body.price).toBe('0.00000000001234');
  });

  it('builds query strings without empty values', () => {
    expect(withQuery('/api/runs', { page: 0, size: 20, datasetId: null })).toBe('/api/runs?page=0&size=20');
    expect(withQuery('/api/x', { pair: 'A/B' })).toBe('/api/x?pair=A%2FB');
  });
});
