import { Button, Group, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { errorMessage } from '../api/client';
import { ErrorState, LoadingState } from './StateViews';

/** The parts of a TanStack query result a page needs to render it over time. */
export interface QueryLike<T> {
  data: T | undefined;
  isError: boolean;
  error: unknown;
  dataUpdatedAt: number;
  refetch: () => unknown;
}

/**
 * Shown above data whose last refresh failed: the data stays on screen (a failed background refetch
 * keeps `data` in TanStack Query v5), with when it was loaded and a retry.
 */
export function RefreshNotice({
  error,
  updatedAt,
  onRetry,
}: {
  error: unknown;
  updatedAt: number;
  onRetry?: () => void;
}) {
  const time = updatedAt > 0 ? new Date(updatedAt).toLocaleTimeString('en-GB') : null;
  return (
    <Group
      role="status"
      gap="sm"
      wrap="wrap"
      px="sm"
      py={6}
      mb="sm"
      style={{
        border: '1px solid var(--mantine-color-default-border)',
        borderRadius: 8,
        background: 'var(--console-raised)',
      }}
    >
      <span aria-hidden style={{ width: 7, height: 7, borderRadius: '50%', background: 'var(--console-rejected)' }} />
      <Text size="sm" style={{ flex: '1 1 240px' }} title={errorMessage(error)}>
        Couldn&apos;t refresh.{' '}
        {time ? (
          <>
            Showing data from <span style={{ fontFamily: 'var(--mantine-font-family-monospace)' }}>{time}</span>.
          </>
        ) : (
          'Showing the last data loaded.'
        )}
      </Text>
      {onRetry && (
        <Button size="compact-xs" variant="default" onClick={onRetry}>
          Retry
        </Button>
      )}
    </Group>
  );
}

/**
 * Renders a query data first: a loader until the first data, the full error state only when there is
 * no data at all, and otherwise the data, with a {@link RefreshNotice} when the latest refresh failed.
 * This keeps tables, forms and open dialogs on screen through a network blip.
 */
export function QueryView<T>({
  query,
  loadingLabel,
  errorTitle,
  children,
}: {
  query: QueryLike<T>;
  loadingLabel?: string;
  errorTitle?: string;
  children: (data: T) => ReactNode;
}) {
  if (query.data === undefined) {
    if (query.isError) {
      return <ErrorState error={query.error} title={errorTitle} onRetry={() => void query.refetch()} />;
    }
    return <LoadingState label={loadingLabel} />;
  }
  return (
    <>
      {query.isError && (
        <RefreshNotice error={query.error} updatedAt={query.dataUpdatedAt} onRetry={() => void query.refetch()} />
      )}
      {children(query.data)}
    </>
  );
}
