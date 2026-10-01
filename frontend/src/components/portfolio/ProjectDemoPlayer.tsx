import { forwardRef, useRef, type ForwardedRef } from 'react'

import { resolveMediaUrl, type ProjectDemo } from '../../types/portfolio'

interface ProjectDemoPlayerProps {
  demo: ProjectDemo
}

function formatTimestamp(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds))
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`
}

/**
 * A project's demo video with its chapter list. `preload="none"` so the page does not start
 * downloading several megabytes of video for a visitor who never presses play; the poster stands
 * in until they do. Captions are on by default because the narration is the only explanation.
 */
export const ProjectDemoPlayer = forwardRef(function ProjectDemoPlayer(
  { demo }: ProjectDemoPlayerProps,
  ref: ForwardedRef<HTMLElement>,
) {
  const videoRef = useRef<HTMLVideoElement>(null)

  const seek = (seconds: number) => {
    const video = videoRef.current
    if (!video) return
    video.currentTime = seconds
    void video.play()?.catch(() => {
      // A browser that refuses to start playback still moved the playhead; nothing to report.
    })
  }

  return (
    <section aria-labelledby="project-demo-title" className="project-demo" id="demo" ref={ref}>
      <div className="project-demo__player">
        <video
          controls
          playsInline
          poster={demo.posterUrl ? resolveMediaUrl(demo.posterUrl) : undefined}
          preload="none"
          ref={videoRef}
          src={resolveMediaUrl(demo.videoUrl)}
        >
          {demo.captionsUrl ? (
            <track default kind="captions" label="English" src={resolveMediaUrl(demo.captionsUrl)} srcLang="en" />
          ) : null}
        </video>
      </div>
      <div className="project-demo__text">
        <p className="project-eyebrow project-eyebrow--on-dark">Demo</p>
        <h2 className="project-demo__title" id="project-demo-title">{demo.title ?? 'Demo'}</h2>
        {demo.summary ? <p className="project-demo__summary">{demo.summary}</p> : null}
        {demo.chapters.length > 0 ? (
          <ol aria-label="Chapters" className="project-demo__chapters">
            {demo.chapters.map(chapter => (
              <li key={`${chapter.startSeconds}-${chapter.label}`}>
                <button className="project-demo__chapter" onClick={() => seek(chapter.startSeconds)} type="button">
                  <time>{formatTimestamp(chapter.startSeconds)}</time>
                  <span>{chapter.label}</span>
                </button>
              </li>
            ))}
          </ol>
        ) : null}
      </div>
    </section>
  )
})
