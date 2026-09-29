/**
 * PROTOTYPE — adpower-style landing hero: the background photo at full strength, a left-aligned
 * two-line statement, plain CTAs, and the "Try the AI assistant" pill in place of the multi-line
 * chat box. Headline and lede are placeholder copy; name, title, location and both images are
 * the live profile.
 */
import { Play, Sparkles } from 'lucide-react'
import { Link } from 'react-router-dom'

import { HeroSection } from '../../components/home/HeroSection'
import { API_BASE_URL } from '../../config/api'
import { useChat } from '../../contexts/ChatContext'
import { useTour } from '../../hooks/useTour'
import type { Profile } from '../../types/Profile'
import { usePrototypeVariant } from './variant'

export function PrototypeAwareHero({ profile }: { profile: Profile }) {
  const variant = usePrototypeVariant()
  if (variant === 'current') {
    return (
      <HeroSection
        name={profile.name}
        title={profile.title}
        tagline={profile.headline}
        backgroundImageUrl={profile.backgroundImage?.url}
      />
    )
  }
  return <HeroPrototype profile={profile} />
}

function HeroPrototype({ profile }: { profile: Profile }) {
  const { openChat } = useChat()
  const { start: startTour } = useTour()
  const desktop = profile.backgroundImage?.url ? `${API_BASE_URL}${profile.backgroundImage.url}` : undefined
  const mobile = profile.mobileBackgroundImage?.url ? `${API_BASE_URL}${profile.mobileBackgroundImage.url}` : desktop

  return (
    <section className="ph-hero">
      {desktop ? (
        <picture>
          {mobile ? <source media="(max-width: 768px)" srcSet={mobile} /> : null}
          <img alt="" className="ph-hero__img" src={desktop} />
        </picture>
      ) : null}
      <div aria-hidden="true" className="ph-hero__shade" />

      <div className="ph-hero__inner">
        <div className="ph-hero__left">
          <div className="ph-hero__copy">
            <p className="ph-hero__eyebrow">
              {profile.name} <span aria-hidden="true">·</span> {profile.title}
              {profile.location ? <><span aria-hidden="true"> · </span>{profile.location}</> : null}
            </p>
            <h1 className="ph-hero__title">
              Leading engineering teams.
              <br />
              <span className="ph-hero__title-accent">Building AI-native systems.</span>
            </h1>
            <p className="ph-hero__lede">
              Real business value, delivered incrementally &mdash; with early feedback, AI-native
              tooling, and teams trusted to build and run what they ship.
            </p>
          </div>
          <div className="ph-hero__actions">
            <div className="ph-hero__ctas">
              <Link className="proto-ask-cta ph-hero__primary" to="/about#roles">See my experience</Link>
              <Link className="ph-hero__textlink" to="/about#contact">Get in touch</Link>
            </div>
            <button className="ph-hero__play" onClick={() => void startTour()} type="button">
              Take the tour <Play fill="currentColor" size={12} />
            </button>
          </div>
        </div>

        <button className="ph-hero__pill" onClick={() => openChat()} type="button">
          <span className="ph-hero__pill-lead">Try the AI assistant</span>
          <span className="ph-hero__pill-main"><Sparkles size={17} /> Ask Simon anything</span>
          <span className="ph-hero__pill-start">Start chat</span>
        </button>
      </div>
    </section>
  )
}
