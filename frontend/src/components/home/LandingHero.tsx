import { Play, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'

import { API_BASE_URL } from '../../config/api'
import { useChat } from '../../contexts/ChatContext'
import { useTour } from '../../hooks/useTour'
import type { HomePageCta, HomePageContent } from '../../types/homePage'
import type { Profile } from '../../types/Profile'

interface LandingHeroProps {
  profile: Profile
  content: HomePageContent
}

/** Site paths route in-app; the backend only lets an `https://` URL through otherwise. */
function CtaLink({ cta, className }: { cta: HomePageCta; className: string }) {
  if (cta.href.startsWith('/')) {
    return <Link className={className} to={cta.href}>{cta.label}</Link>
  }
  return <a className={className} href={cta.href} rel="noopener noreferrer">{cta.label}</a>
}

/**
 * The home page hero: the profile's background photograph at full strength, the CMS-edited
 * two-line statement over it, two calls to action, and the "Ask Simon anything" pill.
 *
 * The copy comes from the Home page editor; the eyebrow and both images come from the
 * profile. Phones get the portrait image with the statement at the top and the actions at the
 * bottom, so the photograph shows between them.
 *
 * The pill carries two load-bearing hooks: `tour-home-chat`, the target of the seeded
 * `default-home-chat` tour step, and `data-ask-anchor`, which keeps the floating Ask pill
 * hidden until this one has scrolled out of view.
 */
export function LandingHero({ profile, content }: LandingHeroProps) {
  const { openChat } = useChat()
  const { start: startTour } = useTour()
  const desktopImage = profile.backgroundImage?.url
    ? `${API_BASE_URL}${profile.backgroundImage.url}`
    : null
  const mobileImage = profile.mobileBackgroundImage?.url
    ? `${API_BASE_URL}${profile.mobileBackgroundImage.url}`
    : desktopImage
  const eyebrow = [profile.name, profile.title, profile.location].filter(Boolean).join(' · ')
  const { askPill } = content

  return (
    <section className="landing-hero">
      {desktopImage ? (
        <picture>
          {mobileImage ? <source media="(max-width: 768px)" srcSet={mobileImage} /> : null}
          <img alt="" className="landing-hero__image" src={desktopImage} />
        </picture>
      ) : null}
      <div aria-hidden="true" className="landing-hero__shade" />

      <div className="landing-hero__inner">
        <div className="landing-hero__left">
          <div className="landing-hero__copy">
            {eyebrow ? <p className="landing-hero__eyebrow">{eyebrow}</p> : null}
            <h1 className="landing-hero__title">
              <span className="landing-hero__title-line">{content.headlineLine1}</span>
              <span className="landing-hero__title-line landing-hero__title-line--accent">
                {content.headlineLine2}
              </span>
            </h1>
            {content.lede ? <p className="landing-hero__lede">{content.lede}</p> : null}
          </div>

          <div className="landing-hero__actions">
            <div className="landing-hero__ctas">
              <CtaLink className="landing-hero__primary" cta={content.primaryCta} />
              {content.secondaryCta ? (
                <CtaLink className="landing-hero__text-link" cta={content.secondaryCta} />
              ) : null}
            </div>
            {content.showTourLink && content.tourLinkLabel ? (
              <button className="landing-hero__tour" onClick={() => void startTour()} type="button">
                {content.tourLinkLabel} <Play aria-hidden="true" fill="currentColor" size={12} />
              </button>
            ) : null}
          </div>
        </div>

        <button
          className="landing-hero__pill tour-home-chat"
          data-ask-anchor
          onClick={() => openChat()}
          type="button"
        >
          {askPill.lead ? <span className="landing-hero__pill-lead">{askPill.lead}</span> : null}
          <span className="landing-hero__pill-label">
            <Sparkles aria-hidden="true" size={17} /> {askPill.label}
          </span>
          <span className="landing-hero__pill-start">{askPill.buttonLabel}</span>
        </button>
      </div>
    </section>
  )
}
