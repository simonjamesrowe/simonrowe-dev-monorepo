/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_RECAPTCHA_SITE_KEY?: string
  readonly VITE_GA_MEASUREMENT_ID?: string
  // Baked in by Dockerfile.frontend from the Publish workflow's github.sha. Absent in
  // local dev, which is why every consumer treats absence as "dev build" rather than
  // an error.
  readonly VITE_GIT_SHA?: string
  readonly VITE_BUILD_TIME?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
