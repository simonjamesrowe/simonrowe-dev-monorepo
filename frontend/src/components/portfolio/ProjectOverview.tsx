import { ExternalLink, Play } from 'lucide-react'
import { useRef } from 'react'
import { Link } from 'react-router-dom'

import { MarkdownRenderer } from '../blog/MarkdownRenderer'
import { resolveMediaUrl, STATUS_LABELS, type PortfolioProject } from '../../types/portfolio'
import { ProjectDemoPlayer } from './ProjectDemoPlayer'
import { ProjectSubnav } from './ProjectSubnav'

interface ProjectOverviewProps {
  project: PortfolioProject
}

/** The headline's first line plain and the rest in the project's accent, as typed in the CMS. */
function Headline({ text }: { text: string }) {
  const [first, ...rest] = text.split('\n')
  return (
    <>
      {first}
      {rest.length > 0 ? (
        <>
          <br />
          <em>{rest.join(' ')}</em>
        </>
      ) : null}
    </>
  )
}

/** The host a live link opens, for the browser frame around the hero image. */
function hostOf(url: string): string | null {
  try {
    return new URL(url).host
  } catch {
    return null
  }
}

/** A question as a link that opens the product with it already asked, when there is one. */
function askUrl(liveUrl: string, question: string): string {
  const url = new URL(liveUrl)
  url.searchParams.set('q', question)
  return url.toString()
}

/**
 * A launched project's overview. Every section after the hero is optional and appears only when
 * the CMS has content for it, so a project with nothing but a description still gets a page.
 */
export function ProjectOverview({ project }: ProjectOverviewProps) {
  const demoRef = useRef<HTMLElement>(null)
  const imageUrl = project.image?.formats?.large?.url ?? project.image?.url
  const host = project.liveUrl ? hostOf(project.liveUrl) : null
  const questions = project.exampleQuestions ?? []
  const highlights = project.highlights ?? []
  const pages = project.pages ?? []
  const points = project.statement?.points ?? []

  return (
    <>
      <div className="project-hero">
        <div className="project-hero__text">
          <p className="project-eyebrow">
            Portfolio · {project.name}
            <span className={`project-card__status project-card__status--${project.status.toLowerCase()}`}>
              {STATUS_LABELS[project.status]}
            </span>
          </p>
          <h1 className="project-hero__title">
            {project.headline ? <Headline text={project.headline} /> : project.name}
          </h1>
          <p className="project-hero__lede">{project.summary ?? project.tagline}</p>
          <div className="project-hero__actions">
            {project.liveUrl ? (
              <a className="button button--primary project-button" href={project.liveUrl} rel="noopener noreferrer" target="_blank">
                Open {project.name} <ExternalLink aria-hidden="true" size={16} />
              </a>
            ) : null}
            {project.demo ? (
              <button
                className="button button--secondary project-button"
                onClick={() => demoRef.current?.scrollIntoView({ behavior: 'smooth', block: 'center' })}
                type="button"
              >
                <Play aria-hidden="true" size={16} /> Watch the demo
              </button>
            ) : null}
          </div>
        </div>
        {imageUrl ? (
          <div className="project-hero__stage">
            <div className="project-hero__browser">
              {host ? (
                <div aria-hidden="true" className="project-hero__browser-bar">
                  <i /><i /><i /><span>{host}</span>
                </div>
              ) : null}
              <img alt="" src={resolveMediaUrl(imageUrl)} />
            </div>
          </div>
        ) : null}
      </div>

      <ProjectSubnav project={project} />

      {project.statement ? (
        <section className="project-statement">
          <div className="project-section__inner">
            {project.statement.label ? <p className="project-eyebrow project-eyebrow--on-band">{project.statement.label}</p> : null}
            {project.statement.text ? <p className="project-statement__text">{project.statement.text}</p> : null}
            {points.length > 0 ? (
              <ul className="project-statement__points">
                {points.map(point => (
                  <li key={point.title}>
                    <strong>{point.title}</strong>
                    {point.text ? <span>{point.text}</span> : null}
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        </section>
      ) : null}

      {questions.length > 0 ? (
        <section aria-labelledby="project-questions" className="project-section">
          <div className="project-section__inner">
            <p className="project-eyebrow">Try asking</p>
            <h2 className="project-section__title" id="project-questions">Questions to try</h2>
            {project.liveUrl ? (
              <p className="project-section__lede">Each one opens {project.name} with the question already asked.</p>
            ) : null}
            <ul className="project-questions">
              {questions.map(question => (
                <li key={question}>
                  {project.liveUrl ? (
                    <a className="project-question" href={askUrl(project.liveUrl, question)} rel="noopener noreferrer" target="_blank">
                      {question}
                    </a>
                  ) : (
                    <span className="project-question">{question}</span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        </section>
      ) : null}

      {highlights.length > 0 ? (
        <section aria-label="What it does" className="project-section">
          <ol className="project-section__inner project-highlights">
            {highlights.map((highlight, index) => (
              <li className="project-highlight" key={highlight.title}>
                <span aria-hidden="true" className="project-highlight__number">{String(index + 1).padStart(2, '0')}</span>
                {highlight.imageUrl ? (
                  <div className="project-highlight__image">
                    <img alt={highlight.imageAlt ?? ''} loading="lazy" src={resolveMediaUrl(highlight.imageUrl)} />
                  </div>
                ) : null}
                <h3 className="project-highlight__title">{highlight.title}</h3>
                {highlight.text ? <p className="project-highlight__text">{highlight.text}</p> : null}
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      {project.description ? (
        <section className="project-section">
          <div className="project-section__inner project-section__inner--prose blog-detail__content">
            <MarkdownRenderer content={project.description} />
          </div>
        </section>
      ) : null}

      {project.demo ? (
        <div className="project-section">
          <div className="project-section__inner">
            <ProjectDemoPlayer demo={project.demo} ref={demoRef} />
          </div>
        </div>
      ) : null}

      {pages.length > 0 ? (
        <section aria-label="More about this project" className="project-section">
          <ul className="project-section__inner project-teasers">
            {pages.map((page, index) => (
              <li key={page.slug}>
                <Link className="project-teaser" to={`/portfolio/${project.slug}/${page.slug}`}>
                  <span className="project-eyebrow">{index === 0 ? 'Next' : 'Then'} · {page.title}</span>
                  {page.summary ? <span className="project-teaser__summary">{page.summary}</span> : null}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  )
}
