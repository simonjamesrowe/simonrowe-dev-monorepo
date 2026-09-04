import type { FactoryNodeBand } from '../../services/softwareFactoryApi'

/**
 * Tab order, and therefore the order a screen reader reads the graph in.
 *
 * Deliberately the main loop rather than the bands: the ring is the thing being communicated, and
 * a keyboard user who cannot see the SVG gets it only from this sequence. Nodes off the ring
 * follow at the end.
 */
export const FACTORY_FLOW_ORDER = [
  'linear', 'build', 'pull-request', 'codereview', 'main', 'deploy', 'production',
  'logwatch', 'cvefix', 'feedback', 'agent-setup', 'platformbackup',
]

/** Fixed grid positions, in an arbitrary 1000x520 viewBox the SVG scales from. */
export const NODE_POSITIONS: Record<string, { x: number; y: number }> = {
  linear: { x: 120, y: 260 },
  build: { x: 300, y: 160 },
  'pull-request': { x: 480, y: 160 },
  codereview: { x: 480, y: 60 },
  main: { x: 660, y: 160 },
  deploy: { x: 820, y: 260 },
  production: { x: 660, y: 380 },
  logwatch: { x: 420, y: 400 },
  cvefix: { x: 660, y: 470 },
  feedback: { x: 300, y: 60 },
  'agent-setup': { x: 140, y: 60 },
  platformbackup: { x: 900, y: 440 },
}

export const BAND_LABELS: Record<FactoryNodeBand, string> = {
  OBSERVE: 'Observe',
  PLAN: 'Plan',
  BUILD: 'Build',
  SHIP: 'Ship',
  LEARN: 'Learn',
  UTILITY: 'Utility',
}
