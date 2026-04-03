import { Terminal, Cloud } from 'lucide-react'

interface StatsGridProps {
  profile: {
    name: string
    title: string
    description: string
    profileImage: { url: string }
  }
}

export function StatsGrid({ profile }: StatsGridProps) {
  return (
    <section className="stats-grid">
      <div className="stats-grid__grid">
        {/* Summary card — spans 2 cols */}
        <div className="card stats-grid__summary">
          <h3 className="headline-md" style={{ color: 'white' }}>The Vision</h3>
          <p className="body-lg" style={{ color: 'var(--on-surface-variant)', marginTop: '1.5rem' }}>
            {profile.description}
          </p>
          <div className="stats-grid__author">
            <img
              src={profile.profileImage.url}
              alt={profile.name}
              className="stats-grid__avatar"
            />
            <div>
              <p className="stats-grid__author-name">{profile.name}</p>
              <p className="stats-grid__author-title">{profile.title}</p>
            </div>
          </div>
        </div>

        {/* Years experience stat — 1 col */}
        <div className="card stats-grid__stat">
          <span className="stats-grid__stat-value stats-grid__stat-value--primary">12+</span>
          <p className="stats-grid__stat-label">Years Experience</p>
        </div>

        {/* Engineers led stat — 1 col */}
        <div className="card stats-grid__stat">
          <span className="stats-grid__stat-value stats-grid__stat-value--secondary">45</span>
          <p className="stats-grid__stat-label">Engineers Led</p>
        </div>

        {/* Capability sub-grid — spans 2 cols, 2x2 */}
        <div className="stats-grid__capabilities">
          <div className="card stats-grid__capability">
            <Terminal size={32} className="stats-grid__capability-icon stats-grid__capability-icon--primary" />
            <div className="stats-grid__capability-text">
              <p className="stats-grid__capability-title">Core Stack</p>
              <p className="stats-grid__capability-desc">Go, Rust, Node, Python</p>
            </div>
          </div>
          <div className="card stats-grid__capability">
            <Cloud size={32} className="stats-grid__capability-icon stats-grid__capability-icon--secondary" />
            <div className="stats-grid__capability-text">
              <p className="stats-grid__capability-title">Cloud Native</p>
              <p className="stats-grid__capability-desc">AWS, K8s, Terraform</p>
            </div>
          </div>
        </div>

        {/* Background overlay card — spans 2 cols */}
        <div className="card stats-grid__showcase">
          <div className="stats-grid__showcase-content">
            <h4 className="headline-md" style={{ color: 'white' }}>Architecting Scale</h4>
            <p style={{ color: 'var(--on-surface-variant)' }}>
              Implementing zero-trust security and sub-second latency global delivery systems.
            </p>
          </div>
        </div>
      </div>
    </section>
  )
}

/*
CSS reference (add to styles.css):

.stats-grid {
  padding: 4rem 1.5rem;
}

.stats-grid__grid {
  max-width: 80rem;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1.5rem;
}

.stats-grid__summary {
  grid-column: span 2;
  display: flex;
  flex-direction: column;
}

.stats-grid__author {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-top: auto;
  padding-top: 1.5rem;
}

.stats-grid__avatar {
  width: 4rem;
  height: 4rem;
  border-radius: 50%;
  object-fit: cover;
  filter: grayscale(100%);
  transition: filter 0.3s ease;
}

.stats-grid__avatar:hover {
  filter: grayscale(0%);
}

.stats-grid__author-name {
  color: white;
  font-weight: 700;
  margin: 0;
}

.stats-grid__author-title {
  color: var(--on-surface-variant);
  font-size: 0.875rem;
  margin: 0;
}

.stats-grid__stat {
  grid-column: span 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 0.5rem;
}

.stats-grid__stat-value {
  font-size: 4rem;
  font-weight: 700;
  line-height: 1;
}

.stats-grid__stat-value--primary {
  color: var(--primary);
}

.stats-grid__stat-value--secondary {
  color: var(--secondary);
}

.stats-grid__stat-label {
  font-size: 0.75rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.15em;
  color: var(--on-surface-variant);
  margin: 0;
}

.stats-grid__capabilities {
  grid-column: span 2;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  grid-template-rows: repeat(2, 1fr);
  gap: 1.5rem;
}

.stats-grid__capability {
  display: flex;
  align-items: center;
  gap: 1rem;
}

.stats-grid__capability-icon {
  flex-shrink: 0;
}

.stats-grid__capability-icon--primary {
  color: var(--primary);
}

.stats-grid__capability-icon--secondary {
  color: var(--secondary);
}

.stats-grid__capability-text {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.stats-grid__capability-title {
  color: white;
  font-weight: 600;
  margin: 0;
}

.stats-grid__capability-desc {
  color: var(--on-surface-variant);
  font-size: 0.875rem;
  margin: 0;
}

.stats-grid__showcase {
  grid-column: span 2;
  min-height: 300px;
  background-color: var(--surface-container);
  background-image: linear-gradient(
    to bottom,
    transparent 40%,
    color-mix(in srgb, var(--surface-container) 90%, transparent)
  );
  display: flex;
  align-items: flex-end;
  position: relative;
  overflow: hidden;
}

.stats-grid__showcase-content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.stats-grid__showcase-content p {
  margin: 0;
}

@media (max-width: 1024px) {
  .stats-grid__grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .stats-grid__summary {
    grid-column: span 2;
  }

  .stats-grid__capabilities {
    grid-column: span 2;
  }

  .stats-grid__showcase {
    grid-column: span 2;
  }
}

@media (max-width: 640px) {
  .stats-grid__grid {
    grid-template-columns: 1fr;
  }

  .stats-grid__summary,
  .stats-grid__stat,
  .stats-grid__capabilities,
  .stats-grid__showcase {
    grid-column: span 1;
  }

  .stats-grid__capabilities {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
  }
}
*/
