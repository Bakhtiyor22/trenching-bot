import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { SAMPLE_DATASET_ID, sampleDataset } from '../../mocks/fixtures';
import { server } from '../../mocks/server';
import { renderPage } from '../../test/render';
import { DatasetsPage } from './DatasetsPage';

function fileInput(container: HTMLElement): HTMLInputElement {
  const input = container.querySelector<HTMLInputElement>('input[type="file"]');
  if (!input) throw new Error('No file input rendered');
  return input;
}

function jsonFile(content: object | string, name = 'dataset.json'): File {
  const text = typeof content === 'string' ? content : JSON.stringify(content);
  return new File([text], name, { type: 'application/json' });
}

describe('DatasetsPage', () => {
  it('lists stored datasets', async () => {
    renderPage(<DatasetsPage />, { path: '/datasets', url: '/datasets' });
    expect(await screen.findByRole('link', { name: 'dip-sample-v3' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'edge-cases' })).toBeInTheDocument();
    expect(screen.getByText('2 datasets')).toBeInTheDocument();
  });

  it('shows the server validation message when an upload is rejected with 400', async () => {
    const serverMessage =
      'Replay checksum mismatch: declared 0000aaaa, computed 7da78ada00b144606af1578d5d72de54f879149f906e785833d6bab9c9e862f6';
    server.use(
      http.post('/api/datasets', () =>
        HttpResponse.json({ code: 'DATASET_INVALID', message: serverMessage, fields: {} }, { status: 400 }),
      ),
    );
    const { user, container } = renderPage(<DatasetsPage />, { path: '/datasets', url: '/datasets' });
    await screen.findByRole('link', { name: 'dip-sample-v3' });

    await user.upload(fileInput(container), jsonFile({ schemaVersion: 1, checksum: '0000aaaa' }, 'tampered.json'));
    await user.click(await screen.findByRole('button', { name: /Upload and validate/ }));

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Upload rejected')).toBeInTheDocument();
    expect(alert).toHaveTextContent(serverMessage);
  });

  it('says when an uploaded dataset already existed', async () => {
    // jsdom's FormData cannot be re-parsed by Node's fetch, so this handler only checks the encoding.
    let contentType = '';
    server.use(
      http.post('/api/datasets', ({ request }) => {
        contentType = request.headers.get('content-type') ?? '';
        return HttpResponse.json({ dataset: sampleDataset, created: false }, { status: 200 });
      }),
    );
    const { user, container } = renderPage(<DatasetsPage />, { path: '/datasets', url: '/datasets' });
    await screen.findByRole('link', { name: 'dip-sample-v3' });

    await user.upload(fileInput(container), jsonFile({ schemaVersion: 1, checksum: SAMPLE_DATASET_ID }));
    await user.type(screen.getByLabelText(/Name/), 'renamed copy');
    await user.click(screen.getByRole('button', { name: /Upload and validate/ }));

    expect(await screen.findByText(/Datasets are identified by their checksum/)).toBeInTheDocument();
    expect(contentType).toMatch(/^multipart\/form-data/);
  });

  it('rejects files over 100 MB before uploading', async () => {
    let uploaded = false;
    server.use(
      http.post('/api/datasets', () => {
        uploaded = true;
        return HttpResponse.json({}, { status: 500 });
      }),
    );
    const { user, container } = renderPage(<DatasetsPage />, { path: '/datasets', url: '/datasets' });
    await screen.findByRole('link', { name: 'dip-sample-v3' });

    const big = jsonFile('{}', 'big.json');
    Object.defineProperty(big, 'size', { value: 101 * 1024 * 1024 });
    await user.upload(fileInput(container), big);

    expect(await screen.findByText('File not accepted')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/limit is 100\.0 MB/);
    expect(screen.queryByRole('button', { name: /Upload and validate/ })).not.toBeInTheDocument();
    expect(uploaded).toBe(false);
  });

  it('imports the sample dataset and reports that it already existed', async () => {
    const { user } = renderPage(<DatasetsPage />, { path: '/datasets', url: '/datasets' });
    await screen.findByRole('link', { name: 'dip-sample-v3' });
    await user.click(screen.getByRole('button', { name: 'Import sample dataset' }));
    expect(await screen.findByText(/Datasets are identified by their checksum/)).toBeInTheDocument();
  });
});
