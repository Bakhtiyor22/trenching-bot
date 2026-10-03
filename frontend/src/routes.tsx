import type { RouteObject } from 'react-router';
import { AppLayout } from './components/AppLayout';
import { NotFoundPage } from './components/NotFoundPage';

export const routes: RouteObject[] = [
  {
    element: <AppLayout />,
    children: [
      {
        index: true,
        lazy: async () => ({
          Component: (await import('./features/dashboard/DashboardPage')).DashboardPage,
        }),
      },
      {
        path: 'datasets',
        lazy: async () => ({
          Component: (await import('./features/datasets/DatasetsPage')).DatasetsPage,
        }),
      },
      {
        path: 'datasets/:id',
        lazy: async () => ({
          Component: (await import('./features/datasets/DatasetDetailPage')).DatasetDetailPage,
        }),
      },
      {
        path: 'runs',
        lazy: async () => ({ Component: (await import('./features/runs/RunsPage')).RunsPage }),
      },
      {
        path: 'runs/new',
        lazy: async () => ({ Component: (await import('./features/runs/NewRunPage')).NewRunPage }),
      },
      {
        path: 'runs/:id',
        lazy: async () => ({
          Component: (await import('./features/runs/RunDetailPage')).RunDetailPage,
        }),
      },
      {
        path: 'solana',
        lazy: async () => ({
          Component: (await import('./features/solana/SolanaPage')).SolanaPage,
        }),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
