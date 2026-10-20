# Software Factory diagrams

These are the canonical Excalidraw element skeletons used by the portfolio page and its films:

- `constraint.json`: file edits on the Pi, builds and tests on GitHub Actions.
- `architecture.json`: factory, deployer, Temporal queues and external systems.
- `repair.json`: approved ticket, three-attempt repair budget and merge.
- `cve.json`: current vulnerability workflow, with human approval and dependency merge.

Run `node docs/diagrams/software-factory/make-diagrams.mjs` to regenerate the JSON in this directory.
The generator is independent of the working directory. The `demo-record` skill's Excalidraw renderer
applies the house style to these skeletons. SVG exports and WebP highlight stills are seeded under
`backend/src/main/resources/seed/portfolio/software-factory/media/` and imported into the CMS media
library by V063.

The approved film sources, scripts, narration, scene timelines and 1080p masters live outside this
repository in `~/workspace/simonjamesrowe/demos/software-factory{,-ships,-log-fix,-cve}/`. Point each
recording's diagram directory at these skeletons when preparing a new cut. The published 1440×810
MP4s, captions and posters are the approved 20 October 2026 cuts. Overview chapters use the final
edited timeline. The CVE film explicitly revisits PR #138 from 30 August 2026; its final diagram
shows today's policy, not the workflow used for that historical repair.

To revise a published page, use the portfolio CMS or a new guarded change unit. Do not edit V063
and expect it to run again on an installation that has already recorded it.

The initial page was verified locally at 1440px and 390px widths, including playback, seeking and
WebVTT captions for every film. These are the launch previews (CMS edits may subsequently differ):

[Desktop overview](previews/overview.webp) · [Mobile walkthrough](previews/walkthrough-mobile.webp)

The cross-origin caption regression is covered by
`cd frontend && npx playwright test e2e/portfolio.local.spec.ts --project=local` with the local
frontend running. It intercepts the portfolio API and serves the real seeded overview film and a
caption track from a separate test origin; it needs no backend. The assertion checks loaded cue
text, because video playback alone succeeds even when the browser rejects the caption track.
