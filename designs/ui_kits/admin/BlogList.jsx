/* global React, Icon, Status, AButton, SearchBar */
const { useState } = React;

const POSTS = [
  { title: "Building AI-native development workflows", status: "published", tags: ["AI", "Engineering"], date: "Mar 14, 2026", views: "4,812" },
  { title: "Event sourcing patterns I keep reaching for", status: "published", tags: ["Kafka", "CQRS"], date: "Feb 02, 2026", views: "3,204" },
  { title: "Why we run weekly evals on every prompt", status: "published", tags: ["AI", "Process"], date: "Jan 18, 2026", views: "2,156" },
  { title: "Notes on Spring Boot 3.4 upgrades", status: "draft", tags: ["Spring"], date: "—", views: "—" },
  { title: "Hiring for engineering taste", status: "scheduled", tags: ["Leadership"], date: "Apr 28, 2026", views: "—" },
  { title: "Kafka outbox in five minutes", status: "draft", tags: ["Kafka"], date: "—", views: "—" },
];

function BlogList({ onOpen }) {
  const [filter, setFilter] = useState("all");
  const [q, setQ] = useState("");
  const filtered = POSTS.filter((p) =>
    (filter === "all" || p.status === filter) &&
    (q === "" || p.title.toLowerCase().includes(q.toLowerCase()))
  );

  return (
    <div className="a-list">
      <div className="a-toolbar">
        <div className="a-tabs">
          {["all", "published", "draft", "scheduled"].map((t) => (
            <button key={t} className={`a-tab ${filter === t ? "active" : ""}`} onClick={() => setFilter(t)}>
              {t[0].toUpperCase() + t.slice(1)}
              <span className="a-tab__count">{t === "all" ? POSTS.length : POSTS.filter(p => p.status === t).length}</span>
            </button>
          ))}
        </div>
        <SearchBar value={q} onChange={setQ} placeholder="Search posts…" />
      </div>

      <table className="a-table">
        <thead>
          <tr>
            <th style={{ width: 24 }}><input type="checkbox" /></th>
            <th>Title</th>
            <th style={{ width: 120 }}>Status</th>
            <th style={{ width: 200 }}>Tags</th>
            <th style={{ width: 120 }}>Date</th>
            <th style={{ width: 90, textAlign: "right" }}>Views</th>
            <th style={{ width: 32 }}></th>
          </tr>
        </thead>
        <tbody>
          {filtered.map((p, i) => (
            <tr key={i} onClick={() => onOpen?.(p)}>
              <td><input type="checkbox" onClick={(e) => e.stopPropagation()} /></td>
              <td className="a-table__title">{p.title}</td>
              <td><Status kind={p.status} /></td>
              <td>
                <div className="a-table__tags">
                  {p.tags.map((t) => <span key={t} className="a-tag">{t}</span>)}
                </div>
              </td>
              <td className="a-table__meta">{p.date}</td>
              <td className="a-table__meta" style={{ textAlign: "right" }}>{p.views}</td>
              <td><button className="a-iconbtn" onClick={(e) => e.stopPropagation()}><Icon name="more-horizontal" size={14} /></button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

window.BlogList = BlogList;
