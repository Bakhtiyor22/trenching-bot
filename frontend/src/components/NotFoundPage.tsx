import { Button } from '@mantine/core';
import { Link } from 'react-router';
import { paths } from '../paths';
import { EmptyState } from './StateViews';

export function NotFoundPage() {
  return (
    <EmptyState
      title="Page not found"
      description="This console page does not exist."
      action={
        <Button component={Link} to={paths.dashboard} variant="light">
          Go to the dashboard
        </Button>
      }
    />
  );
}
