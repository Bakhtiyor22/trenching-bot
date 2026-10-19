import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { renderPage } from '../test/render';
import { SourceLinks } from './SourceLinks';

it('omits the capture crumb for an uploaded dataset', () => {
  renderPage(
    <SourceLinks
      source={{
        captureId: null,
        captureDisplayName: null,
        datasetId: 'dataset',
        datasetName: 'Uploaded sample',
        caseId: null,
      }}
      runId="run-123"
    />,
  );
  expect(screen.queryByText(/capture/i)).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Uploaded sample/ })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Run/ })).toBeInTheDocument();
});
