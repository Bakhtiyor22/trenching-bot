import { setupWorker } from 'msw/browser';
import { createHandlers } from './handlers';

/**
 * Starts in-browser mocks (dev only, `VITE_USE_MOCKS=true`). Solana is mocked as configured so the
 * check and address-check flows can be explored; requests not under /api pass through.
 */
export async function startMockWorker(): Promise<void> {
  const worker = setupWorker(...createHandlers({ solanaConfigured: true, latencyMs: 250 }));
  await worker.start({
    serviceWorker: { url: `${import.meta.env.BASE_URL}mockServiceWorker.js` },
    onUnhandledFrame: 'bypass',
    quiet: false,
  });
}
