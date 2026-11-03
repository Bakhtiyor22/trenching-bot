import type { DatasetSource } from '../../api/types';
import type { Crumb } from '../../components/Panels';
import { shortId } from '../../lib/format';
import { paths } from '../../paths';

/**
 * Where a run's data came from as page-header breadcrumbs: the capture (when there is one) and the dataset,
 * or "Dataset deleted" without a link.
 */
export function sourceCrumbs(source: DatasetSource | undefined): Crumb[] {
  if (!source) return [];
  const crumbs: Crumb[] = [];
  if (source.captureId) {
    crumbs.push({
      label: `Capture: ${source.captureDisplayName ?? shortId(source.captureId)}`,
      to: paths.capture(source.captureId),
    });
  }
  crumbs.push(
    source.datasetName
      ? { label: `Dataset: ${source.datasetName}`, to: paths.dataset(source.datasetId) }
      : { label: 'Dataset deleted' },
  );
  return crumbs;
}
