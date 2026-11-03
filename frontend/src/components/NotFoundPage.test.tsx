import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderPage } from '../test/render';
import { NotFoundPage } from './NotFoundPage';

describe('NotFoundPage', () => {
  it('names the page and links back to the dashboard and runs', () => {
    renderPage(<NotFoundPage />, { path: '*', url: '/no-such-page' });
    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the dashboard' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('link', { name: 'Go to runs' })).toHaveAttribute('href', '/runs');
  });
});
