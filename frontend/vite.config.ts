import react from '@vitejs/plugin-react';
import { msw } from 'msw/vite';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * The console is served by the backend under /console/ (see README), so every build uses that base
 * path; the dev server uses it too, so routes and asset URLs behave the same in both.
 * API calls are absolute (/api, /actuator) and are proxied to the backend in development.
 */
export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const apiTarget = process.env.VITE_API_TARGET ?? env.VITE_API_TARGET ?? 'http://localhost:8081';
  const proxy = { target: apiTarget, changeOrigin: false };
  return {
    base: '/console/',
    // The mock service worker script is served in development only and never emitted into dist.
    plugins: [react(), ...(command === 'serve' ? [msw({ mode: 'worker-only' })] : [])],
    server: {
      port: 5173,
      open: '/console/',
      proxy: { '/api': proxy, '/actuator': proxy },
    },
    preview: { proxy: { '/api': proxy, '/actuator': proxy } },
    build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 1500 },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      css: false,
      restoreMocks: true,
      // Bound concurrent jsdom environments on developer machines and CI runners.
      maxWorkers: 4,
      testTimeout: 15000,
    },
  };
});
