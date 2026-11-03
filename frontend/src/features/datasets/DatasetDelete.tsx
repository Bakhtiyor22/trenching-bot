import { Anchor, Text } from '@mantine/core';
import { Link } from 'react-router';
import { ApiError } from '../../api/client';
import { DATASET_IN_USE } from '../../lib/codes';
import { paths } from '../../paths';

/** What deleting a dataset removes and keeps, in the delete dialog. */
export function DeleteDatasetText({ name }: { name: string }) {
  return (
    <>
      Removes “{name}” from Datasets. Runs that keep their own copy of it are kept. A dataset that
      automation sweep runs read from the catalog cannot be deleted. Uploading the same file again
      restores it under the same id.
    </>
  );
}

/** For a 409 DATASET_IN_USE: why, and where the runs that read the dataset are. */
export function DatasetInUse({ error, datasetId }: { error: unknown; datasetId: string }) {
  if (!(error instanceof ApiError && error.code === DATASET_IN_USE)) return null;
  return (
    <Text size="sm">
      Runs read this dataset from the catalog instead of keeping a copy, so it stays.{' '}
      <Anchor component={Link} to={paths.runsFor(datasetId)}>
        See its runs
      </Anchor>
    </Text>
  );
}
