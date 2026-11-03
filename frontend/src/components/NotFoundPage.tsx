import { Button, Card, Group } from '@mantine/core';
import { Link } from 'react-router';
import { paths } from '../paths';
import { PageHeader } from './Panels';
import { EmptyState } from './StateViews';

/** Any console path without a page: the shared header and a way back to the main pages. */
export function NotFoundPage() {
  return (
    <>
      <PageHeader title="Page not found" description="This console page does not exist." />
      <Card>
        <EmptyState
          title="Nothing at this address"
          description="The link may be old or mistyped. Start again from the dashboard or the list of runs."
          action={
            <Group gap="sm" justify="center">
              <Button component={Link} to={paths.dashboard}>
                Go to the dashboard
              </Button>
              <Button component={Link} to={paths.runs} variant="default">
                Go to runs
              </Button>
            </Group>
          }
        />
      </Card>
    </>
  );
}
