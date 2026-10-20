import { expect, test } from '@playwright/test'
import { fileURLToPath } from 'node:url'

/** Production serves the overview player's media from a separate API origin. */
test('the overview loads captions from a different media origin', async ({ page }) => {
  const mediaOrigin = 'https://portfolio-media.example.test'
  const demoPath = fileURLToPath(new URL(
    '../../backend/src/main/resources/seed/portfolio/software-factory/media/demo.mp4',
    import.meta.url,
  ))
  await page.route(`${mediaOrigin}/demo.mp4`, route => route.fulfill({
    path: demoPath,
    contentType: 'video/mp4',
    headers: { 'Access-Control-Allow-Origin': '*' },
  }))
  await page.route(`${mediaOrigin}/demo.vtt`, route => route.fulfill({
    contentType: 'text/vtt',
    headers: { 'Access-Control-Allow-Origin': '*' },
    body: 'WEBVTT\n\n00:00.000 --> 00:10.000\nCaptions from the media library.\n',
  }))
  await page.route('**/api/portfolio/software-factory', route => route.fulfill({
    json: {
      slug: 'software-factory', name: 'Software Factory', status: 'LIVE', accentHue: 212,
      tagline: 'Small loops on a Raspberry Pi.',
      demo: {
        videoUrl: `${mediaOrigin}/demo.mp4`, captionsUrl: `${mediaOrigin}/demo.vtt`,
        chapters: [{ startSeconds: 0, label: 'Start' }],
      },
    },
  }))

  await page.goto('/portfolio/software-factory')
  const video = page.locator('video')
  await video.evaluate(async (element: HTMLVideoElement) => {
    element.muted = true
    element.textTracks[0].mode = 'showing'
    await element.play()
  })

  // A missing CORS mode still plays the video, but Chromium refuses to load this track.
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => {
    const cues = element.textTracks[0].cues
    return cues?.length ? (cues[0] as VTTCue).text : null
  }), { timeout: 5000 }).toBe('Captions from the media library.')
  await video.evaluate((element: HTMLVideoElement) => element.pause())
})
