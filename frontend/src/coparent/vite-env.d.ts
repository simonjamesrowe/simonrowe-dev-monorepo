/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_COPARENT_AUTH0_CLIENT_ID?: string;
  readonly VITE_COPARENT_AUTH0_AUDIENCE?: string;
  readonly VITE_COPARENT_CANONICAL_ORIGIN?: string;
  readonly VITE_IDLE_TIMEOUT_MINUTES?: string;
  readonly VITE_IDLE_TIMEOUT_SHOW_COUNTDOWN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
