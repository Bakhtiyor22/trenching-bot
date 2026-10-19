import { Anchor, Breadcrumbs, Text } from '@mantine/core';
import { Link } from 'react-router';
import type { DatasetSource } from '../api/types';
import { paths } from '../paths';
import { shortId } from '../lib/format';

export function SourceLinks({
  source,
  runId,
  chart = false,
}: {
  source?: DatasetSource;
  runId?: string;
  chart?: boolean;
}) {
  if (!source) return null;
  return (
    <Breadcrumbs style={{ flexWrap: 'wrap' }}>
      {source.captureId && (
        <Anchor component={Link} to={paths.capture(source.captureId)}>
          Capture: {source.captureDisplayName ?? source.captureId}
        </Anchor>
      )}
      {source.datasetName ? (
        <Anchor component={Link} to={paths.dataset(source.datasetId)}>
          Dataset: {source.datasetName}
        </Anchor>
      ) : (
        <Text size="sm" c="dimmed">
          Dataset deleted
        </Text>
      )}
      {runId && (
        <Anchor component={Link} to={paths.run(runId)}>
          Run {shortId(runId)}
        </Anchor>
      )}
      {chart && <Text size="sm">Chart</Text>}
    </Breadcrumbs>
  );
}
