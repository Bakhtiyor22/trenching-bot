import { act, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { renderPage } from '../test/render';
import { ProviderErrorState } from './StateViews';

it('holds rate-limit retry until the countdown expires', async () => {
  vi.useFakeTimers();
  const retry = vi.fn();
  renderPage(
    <ProviderErrorState
      error={new ApiError(429, 'PROVIDER_RATE_LIMITED', 'Busy', {}, { retryAfterSeconds: 2 })}
      onRetry={retry}
    />,
  );
  expect(screen.getByRole('button', { name: 'Try now' })).toBeDisabled();
  expect(screen.getByText('Technical code: PROVIDER_RATE_LIMITED')).toBeInTheDocument();
  await act(() => vi.advanceTimersByTime(2000));
  expect(screen.getByRole('button', { name: 'Try now' })).toBeEnabled();
  vi.useRealTimers();
});

it('uses the original rate-limit deadline when the error view opens later', async () => {
  vi.useFakeTimers();
  const retryAt = Date.now() + 1000;
  renderPage(
    <ProviderErrorState
      error={new ApiError(429, 'PROVIDER_RATE_LIMITED', 'Busy', {}, { retryAfterSeconds: 20 })}
      retryAt={retryAt}
      onRetry={vi.fn()}
    />,
  );
  expect(screen.getByRole('button', { name: 'Try now' })).toBeDisabled();
  await act(() => vi.advanceTimersByTime(1000));
  expect(screen.getByRole('button', { name: 'Try now' })).toBeEnabled();
  vi.useRealTimers();
});

it.each(['RPC_NOT_CONFIGURED', 'PROVIDER_UNAVAILABLE', 'NOT_MAINNET', 'LOCAL_ACCESS_REQUIRED'])(
  'explains %s and offers the alternative',
  (code) => {
    renderPage(
      <ProviderErrorState
        error={new ApiError(503, code, 'Unavailable')}
        alternatives={<a href="#mint">Paste a token mint instead</a>}
      />,
    );
    expect(screen.getByText(`Technical code: ${code}`)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Paste a token mint instead' })).toBeInTheDocument();
  },
);
