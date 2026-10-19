/** Dev-only mock states, selected by reloading a console URL with ?mockState=... . */
export function mockState(): string | null {
  return typeof window === 'undefined'
    ? null
    : new URLSearchParams(window.location.search).get('mockState');
}
