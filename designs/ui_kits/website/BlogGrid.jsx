/* global React, Section, Pill, Icon */

const POSTS = [
  {
    title: "Building AI-native development workflows",
    excerpt: "How my team uses Claude Code, evals, and structured review to ship faster without losing quality.",
    tags: ["AI", "Engineering"],
    date: "Mar 14",
    read: "8 min",
    grad: "linear-gradient(135deg, #1c2029 0%, #1d3a4a 50%, #2d4a6a 100%)",
    icon: "sparkles",
  },
  {
    title: "Event sourcing patterns I keep reaching for",
    excerpt: "Five years of CQRS in production — what worked, what bit us, and the patterns I'd repeat.",
    tags: ["Kafka", "CQRS"],
    date: "Feb 02",
    read: "12 min",
    grad: "linear-gradient(135deg, #1c2029 0%, #3e2a4a 50%, #4a3060 100%)",
    icon: "git-branch",
  },
  {
    title: "Why we run weekly evals on every prompt",
    excerpt: "A small ritual that catches regressions, builds trust, and makes prompts a real engineering artefact.",
    tags: ["AI", "Process"],
    date: "Jan 18",
    read: "6 min",
    grad: "linear-gradient(135deg, #1c2029 0%, #2a3a2a 50%, #3a5a4a 100%)",
    icon: "check-circle",
  },
];

function BlogGrid() {
  return (
    <Section id="blog" eyebrow="Writing" title="Latest from the" accent="blog"
      sub="Notes on engineering leadership, AI-native delivery, and the patterns I keep coming back to.">
      <div className="sr-blog-grid">
        {POSTS.map((p, i) => (
          <article key={i} className="sr-blog-card">
            <div className="sr-blog-card__cover" style={{ background: p.grad }}>
              <div className="sr-blog-card__icon">
                <Icon name={p.icon} size={28} />
              </div>
            </div>
            <div className="sr-blog-card__body">
              <div className="sr-blog-card__tags">
                {p.tags.map((t) => <Pill key={t} tone="tag">{t}</Pill>)}
              </div>
              <h3>{p.title}</h3>
              <p>{p.excerpt}</p>
              <div className="sr-blog-card__meta">
                <span>{p.date}</span>
                <span className="sr-dot-sep">·</span>
                <span>{p.read} read</span>
              </div>
            </div>
          </article>
        ))}
      </div>
      <div className="sr-blog-grid__more">
        <button className="sr-btn sr-btn--ghost">View all posts <Icon name="arrow-right" size={14} /></button>
      </div>
    </Section>
  );
}

window.BlogGrid = BlogGrid;
