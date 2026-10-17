import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderPage } from '../../test/render';
import { ApiUsagePage } from './ApiUsagePage';

describe('API usage page', () => {
  it('shows units, purposes and tasks and updates the selected period in the URL', async () => {
    const { user, router } = renderPage(<ApiUsagePage />, {
      path: '/usage',
      url: '/usage?captureId=capture-demo',
    });
    expect(await screen.findByText('Read full transactions')).toBeInTheDocument();
    expect(screen.getAllByText('128 requests').length).toBeGreaterThan(0);
    expect(screen.getAllByText('2.5 MB').length).toBeGreaterThan(0);
    expect(screen.getByText('Market step')).toBeInTheDocument();
    expect(screen.getByText(/Gradle CLI captures are not counted/)).toBeInTheDocument();
    await user.click(screen.getByText('24 hours'));
    expect(router.state.location.search).toContain('period=LAST_24_HOURS');
  });
});
