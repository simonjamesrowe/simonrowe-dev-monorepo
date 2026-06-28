/* global React, Section, Pill */

const SKILLS = [
  "Spring Boot", "Kafka", "Event Sourcing", "CQRS", "Kubernetes",
  "AWS", "Microservices", "TypeScript", "React", "AI/LLMs",
];

function About() {
  return (
    <Section id="about" eyebrow="About" title="About" accent="Simon">
      <div className="sr-about">
        <div className="sr-about__photo">
          <div className="sr-photo-frame">
            <div className="sr-photo-placeholder">
              <span>SR</span>
            </div>
          </div>
        </div>
        <div className="sr-about__copy">
          <p className="sr-lead">
            I am driven to achieve real business value by incrementally
            delivering features that compound over time.
          </p>
          <p>
            For the last decade I've been building event-driven and RESTful
            microservices in fin-tech and trading — most recently leading 30+
            engineers at Global, where we ship a commercial trading platform
            across three product pillars.
          </p>
          <p>
            I'm a strong advocate for AI-native engineering — but I believe AI
            amplifies good engineering, it doesn't replace it. Clean code,
            structured evals, and tight feedback loops are where the leverage
            lives.
          </p>
          <div className="sr-about__skills">
            {SKILLS.map((s) => <Pill key={s} tone="tag">{s}</Pill>)}
          </div>
        </div>
      </div>
    </Section>
  );
}

window.About = About;
