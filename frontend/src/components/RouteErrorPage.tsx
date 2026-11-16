import { Anchor, Box, Stack, Text } from '@mantine/core';
import { isRouteErrorResponse, useRouteError } from 'react-router';
import { paths, ROUTER_BASENAME } from '../paths';
import { ErrorState } from './StateViews';

/**
 * The console's own page for an error no view caught (a render error, a failed lazy import): the error in the usual
 * error card with a reload, instead of React Router's "Unexpected Application Error".
 */
export function RouteErrorPage() {
  const error = useRouteError();
  const shown = isRouteErrorResponse(error)
    ? new Error(`${error.status} ${error.statusText}`.trim())
    : error;
  return (
    <Box p="md" maw={720} mx="auto" mt="xl">
      <Stack gap="md">
        <ErrorState
          error={shown}
          title="This page could not be shown"
          onRetry={() => window.location.reload()}
        />
        <Text size="sm" c="dimmed">
          The rest of the console may still work.{' '}
          <Anchor href={`${ROUTER_BASENAME}${paths.dashboard}`} size="sm">
            Go to the dashboard
          </Anchor>
        </Text>
      </Stack>
    </Box>
  );
}
