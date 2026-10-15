import { MantineProvider } from '@mantine/core';
import * as echarts from 'echarts/core';
import { SVGRenderer } from 'echarts/renderers';
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
  it('registers time-axis candlesticks and custom marker renderers', () => {
    echarts.use([SVGRenderer]);
    const instance = echarts.init(null, undefined, { renderer: 'svg', ssr: true, width: 500, height: 300 });
    try {
      instance.setOption({
        animation: false,
        xAxis: { type: 'time', show: false, min: Date.parse('2026-10-15T00:00:00Z'), max: Date.parse('2026-10-15T00:03:00Z') },
        yAxis: { type: 'value', show: false },
        series: [
          { type: 'candlestick', large: false, data: [[Date.parse('2026-10-15T00:01:00Z'), 1, 2, 0.5, 3]], itemStyle: { color: '#12ab34' } },
          { type: 'custom', data: [[Date.parse('2026-10-15T00:01:00Z'), 1]], renderItem: () => ({ type: 'circle', shape: { cx: 20, cy: 20, r: 5 }, style: { fill: '#abcdef' } }) },
        ],
      });
      const svg = instance.renderToSVGString();
      expect(svg).toContain('#12ab34');
      expect(svg).toContain('#abcdef');
    } finally {
      instance.dispose();
    }
  });

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
