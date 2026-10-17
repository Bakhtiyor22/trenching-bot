import type { ApiErrorBody } from './types';

/** All console endpoints live under this prefix (absolute, not affected by the /console/ base). */
export const API_BASE = '/api';

/**
 * A failed call. For a backend 4xx with the standard envelope, `code`, `message` and `fields` are
 * the server's; otherwise `code` is a client-side marker (NETWORK_ERROR, HTTP_ERROR, BAD_RESPONSE).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fields: Record<string, string>;
  readonly suggestion?: string;
  readonly retryAfterSeconds?: number;

  constructor(
    status: number,
    code: string,
    message: string,
    fields: Record<string, string> = {},
    details?: Pick<ApiErrorBody, 'suggestion' | 'retryAfterSeconds'>,
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fields = fields;
    this.suggestion = details?.suggestion;
    this.retryAfterSeconds = details?.retryAfterSeconds;
  }

  /** True for a client error (4xx): retrying the same request cannot succeed. */
  get isClientError(): boolean {
    return this.status >= 400 && this.status < 500;
  }
}

export const NETWORK_ERROR = 'NETWORK_ERROR';
export const HTTP_ERROR = 'HTTP_ERROR';
export const BAD_RESPONSE = 'BAD_RESPONSE';

function isErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) return false;
  const body = value as Record<string, unknown>;
  return typeof body.code === 'string' && typeof body.message === 'string';
}

function fallbackMessage(status: number): string {
  if (status === 404) return 'Not found (HTTP 404). Is the console enabled on the backend?';
  if (status === 413) return 'The upload is too large (HTTP 413).';
  if (status === 502 || status === 503 || status === 504) {
    return `The backend is not reachable (HTTP ${status}). Is it running?`;
  }
  return `Request failed (HTTP ${status}).`;
}

async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '');
  if (text) {
    try {
      const parsed: unknown = JSON.parse(text);
      if (isErrorBody(parsed)) {
        return new ApiError(
          response.status,
          parsed.code,
          parsed.message,
          parsed.fields ?? {},
          parsed,
        );
      }
    } catch {
      // Not JSON (e.g. a proxy error page): fall through to a generic message.
    }
  }
  return new ApiError(response.status, HTTP_ERROR, fallbackMessage(response.status));
}

/** Resolves a root-relative path against the page origin (Node's fetch in tests needs absolute URLs). */
function absolute(url: string): string {
  return typeof window === 'undefined' ? url : new URL(url, window.location.origin).toString();
}

/** Fetches a URL and parses the JSON body, turning every failure into an {@link ApiError}. */
export async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(absolute(url), {
      ...init,
      headers: { Accept: 'application/json', ...(init.headers ?? {}) },
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError(0, NETWORK_ERROR, 'Cannot reach the backend. Is it running?');
  }
  if (!response.ok) throw await toApiError(response);
  const text = await response.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(
      response.status,
      BAD_RESPONSE,
      'The backend returned a response that is not JSON.',
    );
  }
}

export function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  return request<T>(url, { signal });
}

export function postJson<T>(url: string, body?: unknown): Promise<T> {
  return request<T>(url, {
    method: 'POST',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

/** Sends a DELETE. A 404 means it is already gone, which a confirmed delete treats as done. */
export async function deleteRequest(url: string): Promise<void> {
  try {
    await request<void>(url, { method: 'DELETE' });
  } catch (error) {
    if (!(error instanceof ApiError && error.status === 404)) throw error;
  }
}

export function postForm<T>(url: string, form: FormData): Promise<T> {
  // No Content-Type header: the browser sets the multipart boundary.
  return request<T>(url, { method: 'POST', body: form });
}

/** Builds `path?query`, skipping undefined/null/empty values. */
export function withQuery(
  path: string,
  query: Record<string, string | number | null | undefined>,
): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

/** Human-readable message for any thrown value. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Unexpected error.';
}
