import { MantineProvider } from '@mantine/core';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { theme } from '../theme';
import { RouteErrorPage } from './RouteErrorPage';

function Broken(): never {
  throw new TypeError('run.mints is not iterable');
}

describe('RouteErrorPage', () => {
  it('shows a render error in the console error card instead of the router default', async () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {});
    const router = createMemoryRouter(
      [{ path: '/', element: <Broken />, errorElement: <RouteErrorPage /> }],
      { initialEntries: ['/'] },
    );
    render(
      <MantineProvider theme={theme} env="test">
        <RouterProvider router={router} />
      </MantineProvider>,
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('run.mints is not iterable');
    expect(screen.getByText('This page could not be shown')).toBeInTheDocument();
    expect(screen.queryByText(/Unexpected Application Error/)).toBeNull();
    quiet.mockRestore();
  });
});
