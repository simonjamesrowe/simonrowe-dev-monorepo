# Term Time demo and page assets

Sources for the media behind `/portfolio/term-time`, which ship in
`frontend/public/media/portfolio/term-time/`.

- `script.mjs` and `brief.md` are the narrated demo, built with the `demo-record` skill from a
  demos workspace (`~/workspace/simonjamesrowe/demos/term-time/`). Chat scenes run read-only
  against production; admin scenes need the local stack with Term Time on, a restored production
  backup, and a signed-in `auth.json` (never committed). Record at `deviceScaleFactor` 1: at 2
  the diagram focus overlay drifts off its boxes. The site copy is the build's MP4 re-encoded with `ffmpeg -c:v libx264 -preset slow -crf 27 -c:a aac -b:a 96k -movflags +faststart`.
- `letter.html` is the made-up spelling list the photo scene reads; render it to `assets/letter.jpg`.
- `capture-screens.mjs` takes the production chat screenshots and `capture-admin.mjs` the admin
  one; `export-svg.mjs` renders `docs/diagrams/term-time/*.json` to the SVGs.

If the demo's chapters move, update `demo.chapters` in
`backend/src/main/resources/seed/portfolio/term-time/project.json` for new installs, and in the
CMS for production.
