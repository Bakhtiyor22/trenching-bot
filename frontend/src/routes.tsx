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
        path: 'runs/:id/chart',
        lazy: async () => ({
          Component: (await import('./features/results/RunChartPage')).RunChartPage,
        }),
      },
      {
        path: 'runs/:id',
        lazy: async () => ({
          Component: (await import('./features/runs/RunDetailPage')).RunDetailPage,
        }),
      },
      {
        path: 'compare',
        lazy: async () => ({
          Component: (await import('./features/compare/ComparePage')).ComparePage,
        }),
      },
      {
        path: 'captures',
        lazy: async () => ({
          Component: (await import('./features/captures/CapturesPage')).CapturesPage,
        }),
      },
      {
        path: 'captures/new',
        lazy: async () => ({
          Component: (await import('./features/captures/NewCapturePage')).NewCapturePage,
        }),
      },
      {
        path: 'usage',
        lazy: async () => ({
          Component: (await import('./features/usage/ApiUsagePage')).ApiUsagePage,
        }),
      },
      {
        path: 'automation',
        lazy: async () => ({
          Component: (await import('./features/automation/AutomationOverviewPage'))
            .AutomationOverviewPage,
        }),
      },
      {
        path: 'automation/days',
        lazy: async () => ({
          Component: (await import('./features/automation/AutomationDaysPage')).AutomationDaysPage,
        }),
      },
      {
        path: 'automation/days/:date',
        lazy: async () => ({
          Component: (await import('./features/automation/AutomationDayPage')).AutomationDayPage,
        }),
      },
      {
        path: 'automation/backfills',
        lazy: async () => ({
          Component: (await import('./features/automation/BackfillsPage')).BackfillsPage,
        }),
      },
      {
        path: 'automation/backfills/:id',
        lazy: async () => ({
          Component: (await import('./features/automation/BackfillDetailPage')).BackfillDetailPage,
        }),
      },
      {
        path: 'automation/nights',
        lazy: async () => ({
          Component: (await import('./features/automation/AutomationRedirects')).NightsRedirect,
        }),
      },
      {
        path: 'automation/nights/:id',
        lazy: async () => ({
          Component: (await import('./features/automation/AutomationRedirects')).NightRedirect,
        }),
      },
      {
        path: 'automation/items/:id',
        lazy: async () => ({
          Component: (await import('./features/automation/AutomationRedirects')).ItemRedirect,
        }),
      },
      {
        path: 'automation/sweep-runs',
        lazy: async () => ({
          Component: (await import('./features/automation/AutomationSweepRunsPage'))
            .AutomationSweepRunsPage,
        }),
      },
      {
        path: 'captures/:id',
        lazy: async () => ({
          Component: (await import('./features/captures/CaptureDetailPage')).CaptureDetailPage,
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
