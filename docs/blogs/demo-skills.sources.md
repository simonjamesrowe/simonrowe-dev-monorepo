# Metadata and media provenance — demo-skills

## CMS metadata (proposed)

- **Title:** I'm Rubbish at Recording Demos, So I Taught Claude to Do It
- **Short description:** Two Claude Code skills that grill me for the story, then voice, film and
  mix a narrated demo. Here's how they made the Term Time and Clinician's Veil walkthroughs.
- **Content type:** ENGINEERING
- **Tags:** Claude Code, Agents, AI, Playwright
- **Skills:** Claude Code, AI-Assisted Development

## Publishing dependency

The post links `/portfolio/clinicians-veil`, which was Coming soon until
`V050SeedCliniciansVeilProjectPage` launched it. Publish the post only once that page is live.

## Featured image

- **Photo:** "Person holding clapperboard"
- **Source:** https://unsplash.com/photos/person-holding-clapperboard-Hn3S90f6aak
- **Credit:** Avel Chuklanov, via Unsplash
- **Licence:** Unsplash License — free to use, attribution optional
- **Prepared as:** 1600px wide JPEG (`sips -Z 1600`), kept in `.context/blog/`, not committed

## Inline media

- Term Time demo poster: already in the media library as
  `/uploads/a877cd36-ac56-4550-88fb-07843968f6f4/original.webp` (the project page's `posterUrl`).
- `docs/diagrams/demo-skills/pipeline.svg`: upload through the CMS media workflow and replace the
  draft's local path with the returned `/uploads/...` path.

## Diagram

`docs/diagrams/demo-skills/make-diagrams.mjs` writes `pipeline.json` (Excalidraw skeletons), and
`export-svg.mjs pipeline [--png]` renders it with demo-record's own diagram renderer, so the figure
shares the demos' house style. The SVG embeds Excalifont and has an opaque white background for
the dark theme.

## Sources checked

- Dan Vega, "Claude Code skills: not just for coding":
  https://www.danvega.dev/blog/claude-code-skills-not-just-for-coding
- Matt Pocock's skills (grilling): https://github.com/mattpocock/skills
- The skills themselves: `agent-setup/components/skills/demo-plan` and `demo-record`
  (first version 29 Sep 2026, PR #30; native macOS mode PR #35, 3 Oct 2026)
- Demo briefs, plans, scripts and build timelines in `~/workspace/simonjamesrowe/demos/`
- Session transcripts for the build history, takes and feedback quoted in the post
