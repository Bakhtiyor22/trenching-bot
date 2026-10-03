/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" serves /api from in-browser MSW mocks (dev server only). */
  readonly VITE_USE_MOCKS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
