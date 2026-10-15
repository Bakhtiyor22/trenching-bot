import '@testing-library/jest-dom/vitest';
import { notifications } from '@mantine/notifications';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, vi } from 'vitest';
import { resetMockDb } from '../mocks/handlers';
import { server } from '../mocks/server';

// Dependent API queries and concurrent jsdom workers can exceed the default one-second wait.
configure({ asyncUtilTimeout: 5_000 });

/** Serializes a chart option for assertions; functions (formatters) are dropped. */
function serializeOption(option: unknown): string {
  return JSON.stringify(option, (_key, value: unknown) =>
    typeof value === 'function' ? undefined : value,
  );
}

/**
 * A minimal ECharts instance for overlays that place HTML over the plot: x is 40 px per second of
 * the minute, y a fixed baseline, so actions a second or more apart never stack.
 */
function fakeChartInstance(dom: HTMLElement) {
  const noop = () => {};
  return {
    convertToPixel: (_finder: unknown, values: number[]) => [((values[0]! / 1000) % 60) * 40 + 40, 200],
    containPixel: () => true,
    on: noop,
    off: noop,
    isDisposed: () => false,
    getDom: () => dom,
    getZr: () => ({ on: noop, off: noop }),
  };
}

// ECharts needs a real canvas, which jsdom lacks. Render a stub exposing the option as JSON so tests
// can still assert what would be plotted, and hand callers a fake instance once mounted.
vi.mock('echarts-for-react/esm/core', async () => {
  const { createElement, useEffect, useRef } = await import('react');
  function EChartsStub({ option, onChartReady }: { option: unknown; onChartReady?: (instance: unknown) => void }) {
    const dom = useRef<HTMLDivElement>(null);
    const ready = useRef(onChartReady);
    // Ready fires once per mount, as in echarts-for-react.
    useEffect(() => {
      if (dom.current) ready.current?.(fakeChartInstance(dom.current));
    }, []);
    return createElement('div', { ref: dom, 'data-testid': 'echarts', 'data-option': serializeOption(option) });
  }
  return { default: EChartsStub };
});

// Browser APIs Mantine relies on that jsdom lacks.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
if (!('ResizeObserver' in window)) {
  Object.defineProperty(window, 'ResizeObserver', { writable: true, value: ResizeObserverStub });
}
if (!window.HTMLElement.prototype.scrollIntoView) {
  window.HTMLElement.prototype.scrollIntoView = () => {};
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  cleanup();
  // The notifications store is module-level; clear it so toasts don't leak into the next test.
  notifications.clean();
  server.resetHandlers();
  resetMockDb();
});
afterAll(() => server.close());
