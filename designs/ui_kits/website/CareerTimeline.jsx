/* global React, Section, Pill */

const ROLES = [
  {
    role: "Head of Engineering",
    org: "Global · Commercial Trading",
    logo: "G",
    dates: "Aug 2021 — Present",
    location: "Holborn, London",
    desc: "Leading 30+ engineers across three product pillars. Driving AI-native delivery and event-driven architecture on Kafka + Spring Boot.",
    tags: ["Leadership", "AI-Native", "Kafka", "AWS"],
    current: true,
  },
  {
    role: "Lead Engineer",
    org: "Y-Tree · Wealthtech",
    logo: "Y",
    dates: "Jan 2019 — Jul 2021",
    location: "London",
    desc: "Built event-driven microservices on Kafka + Spring Boot for portfolio analytics. Embedded eval-first patterns into the backend pipeline.",
    tags: ["Spring Boot", "Kafka", "Event Sourcing"],
  },
  {
    role: "Senior Engineer",
    org: "Upp · AdTech",
    logo: "U",
    dates: "Mar 2016 — Dec 2018",
    location: "London",
    desc: "Scaled programmatic ad-bidding services to peak 50k req/s. Owned platform observability, latency budgets, and CI/CD.",
    tags: ["Microservices", "Java", "AWS"],
  },
  {
    role: "Engineer · Pivotal Labs",
    org: "Pivotal Software · Consultancy",
    logo: "P",
    dates: "Sep 2014 — Feb 2016",
    location: "London",
    desc: "TDD, pairing, and weekly retros across a dozen client engagements. This is where the craftsmanship habit started.",
    tags: ["TDD", "Pairing", "Spring"],
  },
  {
    role: "Software Engineer",
    org: "Universal Music Publishing Group",
    logo: "U",
    dates: "Jul 2011 — Aug 2014",
    location: "London",
    desc: "Built royalty-tracking and rights-management services for the world's largest music publisher. First taste of building software people actually depend on.",
    tags: ["Java", "Spring", "Oracle"],
  },
  {
    role: "BSc Computer Science",
    org: "University · WAM 81.24",
    logo: "🎓",
    dates: "2008 — 2011",
    location: "Sydney",
    desc: "Distinction-average Computer Science degree. Final-year focus on distributed systems and concurrent programming.",
    tags: ["Education"],
    edu: true,
  },
];

function CareerTimeline() {
  return (
    <Section id="career" eyebrow="Career" title="Career" accent="Timeline"
      sub="A journey through engineering leadership, platform architecture, and software craftsmanship.">
      <div className="sr-tl">
        <div className="sr-tl__spine" />
        {ROLES.map((r, i) => (
          <div key={i} className={`sr-tl__row ${i % 2 === 0 ? "left" : "right"}`}>
            <div className={`sr-tl__dot ${r.current ? "current" : ""} ${r.edu ? "warm" : ""}`} />
            <div className="sr-tl__year">{r.dates.split(" — ")[0]}</div>
            <article className={`sr-tl__card ${r.edu ? "edu" : ""}`}>
              <header>
                <div className="sr-tl__logo">{r.logo}</div>
                <div>
                  <h3>{r.role}</h3>
                  <div className="sr-tl__org">{r.org}</div>
                </div>
                {r.current && <span className="sr-pill sr-pill--current">● Current</span>}
              </header>
              <div className="sr-tl__meta">
                <span>{r.dates}</span>
                <span className="sr-dot-sep">·</span>
                <span>{r.location}</span>
              </div>
              <p className="sr-tl__desc">{r.desc}</p>
              <div className="sr-tl__tags">
                {r.tags.map((t) => <Pill key={t} tone={r.edu ? "warm" : "tag"}>{t}</Pill>)}
              </div>
            </article>
          </div>
        ))}
      </div>
    </Section>
  );
}

window.CareerTimeline = CareerTimeline;
