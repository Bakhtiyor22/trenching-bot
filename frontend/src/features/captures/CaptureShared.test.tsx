import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { renderPage } from '../../test/render';
import { CommandError } from './CaptureShared';

describe('Qualification command errors', () => {
  it.each([
    ['EXPORT_CHANGED', 'The exported dataset changed or is missing since export. Export again, then qualify.'],
    ['EXPORT_REQUIRED', 'Export the dataset before qualifying.'],
    ['UNSUPPORTED_SCOPE', 'Qualification is only available for wallet + token sessions.'],
  ])('shows guidance for %s while retaining the backend message', (code, guidance) => {
    renderPage(<CommandError error={new ApiError(409, code, 'Backend qualification detail')} uncertain={false} retry={() => {}} />);
    expect(screen.getByText('Backend qualification detail')).toBeInTheDocument();
    expect(screen.getByText(guidance)).toBeInTheDocument();
  });

  it('preserves unrecognized error messages and field details', () => {
    renderPage(<CommandError error={new ApiError(409, 'UNRECOGNIZED_REASON', 'Backend action detail', { step: 'Check the capture stage' })} uncertain={true} retry={() => {}} />);
    expect(screen.getByText('Backend action detail')).toBeInTheDocument();
    expect(screen.getByText('step: Check the capture stage')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reconcile and retry request' })).toBeInTheDocument();
    expect(screen.queryByText('unrecognized reason')).not.toBeInTheDocument();
  });
});
