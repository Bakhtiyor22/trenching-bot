import '@mantine/core/styles.css';
import '@mantine/dropzone/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/spotlight/styles.css';
import './styles.css';

import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { createQueryClient } from './api/queries';
import { ROUTER_BASENAME } from './paths';
import { routes } from './routes';
import { theme } from './theme';

const router = createBrowserRouter(routes, { basename: ROUTER_BASENAME });

export function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <MantineProvider theme={theme} defaultColorScheme="dark">
      <Notifications position="bottom-right" />
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>
  );
}
