/* global React, StatCard, Icon, Status */

const RECENT = [
  { kind: "publish", title: "Building AI-native development workflows", when: "2h ago", icon: "file-text" },
  { kind: "chat",    title: "324 chat sessions in the last 24h",        when: "Today",  icon: "message-circle" },
  { kind: "event",   title: "QCon London — Talk submitted",             when: "Yesterday", icon: "calendar" },
  { kind: "edit",    title: "Edited: Event sourcing patterns…",         when: "2 days ago", icon: "edit-3" },
  { kind: "media",   title: "Uploaded 4 images to /uploads/blog",       when: "3 days ago", icon: "image" },
];

const POPULAR = [
  { title: "Building AI-native development workflows", views: "4,812", trend: "+18%" },
  { title: "Event sourcing patterns I keep reaching for", views: "3,204", trend: "+9%" },
  { title: "Why we run weekly evals on every prompt", views: "2,156", trend: "+24%" },
];

function Dashboard() {
  return (
    <div className="a-dash">
      <div className="a-dash__stats">
        <StatCard label="Posts published" value="42" delta="▲ 3 this month" icon="file-text" />
        <StatCard label="Chat sessions" value="1,284" delta="▲ 12% w/w" icon="message-circle" accent />
        <StatCard label="Upcoming events" value="5" delta="2 next week" deltaTone="warm" icon="calendar" />
        <StatCard label="Media items" value="216" delta="48 GB used" deltaTone="neutral" icon="image" />
      </div>

      <div className="a-dash__grid">
        <section className="a-card">
          <header className="a-card__head">
            <h3>Recent activity</h3>
            <a className="a-link">View all <Icon name="arrow-right" size={12} /></a>
          </header>
          <ul className="a-activity">
            {RECENT.map((r, i) => (
              <li key={i}>
                <div className={`a-activity__icon a-activity__icon--${r.kind}`}>
                  <Icon name={r.icon} size={14} />
                </div>
                <div className="a-activity__body">
                  <div className="a-activity__title">{r.title}</div>
                  <div className="a-activity__when">{r.when}</div>
                </div>
              </li>
            ))}
          </ul>
        </section>

        <section className="a-card">
          <header className="a-card__head">
            <h3>Popular posts <span className="a-card__sub">last 30 days</span></h3>
          </header>
          <ul className="a-popular">
            {POPULAR.map((p, i) => (
              <li key={i}>
                <div className="a-popular__rank">0{i + 1}</div>
                <div className="a-popular__body">
                  <div className="a-popular__title">{p.title}</div>
                  <div className="a-popular__meta">
                    <span><Icon name="eye" size={11} /> {p.views}</span>
                    <span className="a-popular__trend">{p.trend}</span>
                  </div>
                </div>
                <div className="a-popular__sparkline" aria-hidden="true">
                  <svg viewBox="0 0 80 24" width="80" height="24">
                    <polyline fill="none" stroke="currentColor" strokeWidth="1.5"
                      points={`0,${20 - i * 2} 12,${16 - i} 24,${18 - i * 3} 36,${10 + i} 48,${8 - i} 60,${12 - i * 2} 72,${5}`} />
                  </svg>
                </div>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  );
}

window.Dashboard = Dashboard;
