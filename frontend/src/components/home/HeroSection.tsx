import { Download, Link, Share2 } from 'lucide-react'

interface HeroSectionProps {
  name: string
  tagline: string
  cvUrl?: string | null
  children?: React.ReactNode
}

export function HeroSection({ name, tagline, cvUrl, children }: HeroSectionProps) {
  const nameParts = name.split(' ')
  const firstName = nameParts.slice(0, -1).join(' ')
  const lastName = nameParts[nameParts.length - 1]

  return (
    <section className="hero">
      <div className="hero__blur hero__blur--primary" />
      <div className="hero__blur hero__blur--secondary" />
      <div className="hero__grid">
        <div className="hero__content">
          <span className="chip hero__chip">Engineering Leadership // AI-Native Systems</span>
          <h1 className="hero__title display-lg">
            {firstName} <span className="hero__title-accent">{lastName}</span>
          </h1>
          <p className="hero__tagline body-lg">
            {tagline}
          </p>
          <div className="hero__actions">
            {cvUrl && (
              <a href="/api/resume" target="_blank" rel="noopener noreferrer" className="button button--primary">
                Download CV <Download size={18} />
              </a>
            )}
            <a href="#" className="button button--secondary">View Github</a>
            <div className="hero__social-icons">
              <a href="#" className="hero__icon-btn"><Link size={20} /></a>
              <a href="#" className="hero__icon-btn"><Share2 size={20} /></a>
            </div>
          </div>
        </div>
        <div className="hero__chat">
          {children}
        </div>
      </div>
    </section>
  )
}

/*
CSS reference (add to styles.css):

.hero {
  position: relative;
  min-height: calc(100vh - 80px);
  display: flex;
  align-items: center;
  overflow: hidden;
}

.hero__blur {
  position: absolute;
  width: 24rem;
  height: 24rem;
  border-radius: 50%;
  filter: blur(120px);
  pointer-events: none;
}

.hero__blur--primary {
  background-color: color-mix(in srgb, var(--primary) 10%, transparent);
  top: -4rem;
  left: -4rem;
}

.hero__blur--secondary {
  background-color: color-mix(in srgb, var(--secondary) 5%, transparent);
  bottom: -4rem;
  right: -4rem;
}

.hero__grid {
  max-width: 72rem;
  margin: 0 auto;
  padding: 0 1.5rem;
  width: 100%;
  display: grid;
  grid-template-columns: repeat(12, 1fr);
  gap: 2rem;
  align-items: center;
}

.hero__content {
  grid-column: span 7;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.hero__chat {
  grid-column: span 5;
}

.hero__chip {
  display: inline-block;
  width: fit-content;
}

.hero__title {
  color: #fff;
  margin: 0;
}

.hero__title-accent {
  color: var(--primary);
}

.hero__tagline {
  color: var(--on-surface-variant);
  max-width: 42rem;
  margin: 0;
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem;
}

.hero__social-icons {
  display: flex;
  gap: 0.5rem;
}

.hero__icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 50%;
  color: var(--on-surface-variant);
  transition: color 0.2s ease, background-color 0.2s ease;
}

.hero__icon-btn:hover {
  color: var(--primary);
  background-color: color-mix(in srgb, var(--primary) 10%, transparent);
}
*/
