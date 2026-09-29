export interface HomePageCta {
  label: string
  /** A site path (`/about#roles`) or an absolute `https://` URL — the backend refuses anything else. */
  href: string
}

export interface HomePageAskPill {
  lead: string | null
  label: string
  buttonLabel: string
}

/** The home page hero's CMS-edited copy. `updatedAt` is null until the first save. */
export interface HomePageContent {
  headlineLine1: string
  headlineLine2: string
  lede: string | null
  primaryCta: HomePageCta
  secondaryCta: HomePageCta | null
  showTourLink: boolean
  tourLinkLabel: string | null
  askPill: HomePageAskPill
  updatedAt: string | null
}

/**
 * Shown if the hero copy cannot be fetched, so the landing page is never blank. Mirrors the
 * backend's `HomePageDefaults`, which is what the API itself serves before anything is saved;
 * this copy only matters when the API is unreachable.
 */
export const DEFAULT_HOME_PAGE: HomePageContent = {
  headlineLine1: 'Leading engineering teams.',
  headlineLine2: 'Building AI-native systems.',
  lede: 'I lead a 30-strong engineering function across three product pillars, delivering real '
    + 'business value incrementally with AI-native tooling and teams trusted to run what they ship.',
  primaryCta: { label: 'See my experience', href: '/about#roles' },
  secondaryCta: { label: 'Get in touch', href: '/about#contact' },
  showTourLink: true,
  tourLinkLabel: 'Take a tour',
  askPill: { lead: 'Got a question?', label: 'Ask Simon anything', buttonLabel: 'Start chat' },
  updatedAt: null,
}
