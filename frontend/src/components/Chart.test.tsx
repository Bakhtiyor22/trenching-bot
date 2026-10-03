import { MantineProvider } from '@mantine/core';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { basename, join, resolve, sep } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { optimizeDeps, resolveConfig } from 'vite';
import { describe, expect, it, vi } from 'vitest';
import { Chart } from './Chart';

vi.unmock('echarts-for-react/esm/core');

describe('Chart package integration', () => {
  it('exports a React component after Vite development optimization', async () => {
    const cacheDirectory = await mkdtemp(join(tmpdir(), 'console-chart-interop-'));
    if (
      !cacheDirectory.startsWith(resolve(tmpdir()) + sep) ||
      !basename(cacheDirectory).startsWith('console-chart-interop-')
    ) {
      throw new Error('Unexpected chart test cache directory');
    }
    try {
      const binding = await readFile(resolve('src/components/chartComponent.ts'), 'utf8');
      const dependency = binding.match(/from '([^']+)'/)?.[1];
      if (!dependency) throw new Error('Missing chart package binding');
      const config = await resolveConfig(
        {
          configFile: false,
          envFile: false,
          logLevel: 'silent',
          cacheDir: cacheDirectory,
          optimizeDeps: { include: [dependency], noDiscovery: true },
        },
        'serve',
      );
      const metadata = await optimizeDeps(config, true);
      const core = Object.values(metadata.optimized).find((item) =>
        item.id.startsWith('echarts-for-react/'),
      );
      if (!core) throw new Error('Missing optimized chart dependency');
      const bundle: { default: unknown } = createRequire(resolve('package.json'))(core.file);

      expect(typeof bundle.default).toBe('function');
    } finally {
      await rm(cacheDirectory, { recursive: true, force: true });
    }
  });

  it('renders the actual chart component without a mocked package export', () => {
    const markup = renderToStaticMarkup(
      <MantineProvider env="test">
        <Chart ariaLabel="Price history" option={{ series: [] }} />
      </MantineProvider>,
    );

    expect(markup).toContain('aria-label="Price history"');
    expect(markup).toContain('echarts-for-react');
  });
});
