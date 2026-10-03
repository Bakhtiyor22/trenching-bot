import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import {
  createMemoryRouter,
  RouterProvider,
  type InitialEntry,
  type RouteObject,
} from 'react-router';
import { createQueryClient } from '../api/queries';
import { theme } from '../theme';

/**
 * Renders a page inside the app providers with an in-memory router. `path` is the route pattern
 * (e.g. "/runs/:id") and `url` the initial location.
 */
export function renderPage(
  element: ReactElement,
  {
    path = '/',
    url = '/',
    extraRoutes = [],
  }: { path?: string; url?: InitialEntry; extraRoutes?: RouteObject[] } = {},
) {
  const queryClient = createQueryClient();
  queryClient.setDefaultOptions({
    queries: { ...queryClient.getDefaultOptions().queries, retry: false },
  });
  const router = createMemoryRouter([{ path, element }, ...extraRoutes], { initialEntries: [url] });
  const user = userEvent.setup();
  const result = render(
    <MantineProvider theme={theme} env="test">
      <Notifications />
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { ...result, user, router, queryClient };
}
