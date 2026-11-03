import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { theme } from '../theme';
import { QueryView, type QueryLike } from './QueryView';

function view<T>(query: QueryLike<T>) {
  return render(
    <MantineProvider theme={theme} env="test">
      <QueryView query={query} errorTitle="Could not load runs">
        {(data) => <p>rows: {String(data)}</p>}
      </QueryView>
    </MantineProvider>,
  );
}

const failure = new ApiError(503, 'UNAVAILABLE', 'Backend unreachable');

describe('QueryView', () => {
  it('shows a loader before the first data', () => {
    view({ data: undefined, isError: false, error: null, dataUpdatedAt: 0, refetch: vi.fn() });
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
  });

  it('shows the full error only when there is no data', () => {
    view({ data: undefined, isError: true, error: failure, dataUpdatedAt: 0, refetch: vi.fn() });
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load runs');
    expect(screen.queryByText(/rows:/)).not.toBeInTheDocument();
  });

  it('keeps the data on screen when a background refresh fails, with a retry', async () => {
    const refetch = vi.fn();
    view({ data: 3, isError: true, error: failure, dataUpdatedAt: Date.UTC(2026, 10, 3, 9, 2, 10), refetch });
    expect(screen.getByText('rows: 3')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/Couldn.t refresh/);
    screen.getByRole('button', { name: 'Retry' }).click();
    expect(refetch).toHaveBeenCalledOnce();
  });

  it('renders the data alone after a successful load', () => {
    view({ data: 'ok', isError: false, error: null, dataUpdatedAt: 1, refetch: vi.fn() });
    expect(screen.getByText('rows: ok')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
