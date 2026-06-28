/* global React, Icon */
const { useState } = React;

const NAV = [
  { id: "dashboard", icon: "layout-dashboard", label: "Dashboard" },
  { id: "blog",      icon: "file-text",        label: "Blog",        count: 42 },
  { id: "events",    icon: "calendar",         label: "Events",      count: 5 },
  { id: "experience",icon: "briefcase",        label: "Experience" },
  { id: "media",     icon: "image",            label: "Media" },
];

function AdminLayout({ route, setRoute, children, title, subtitle, actions }) {
  return (
    <div className="a-shell">
      <aside className="a-sidebar">
        <div className="a-sidebar__brand">
          <div className="a-sidebar__mark">S</div>
          <div>
            <div className="a-sidebar__title">Admin</div>
            <div className="a-sidebar__sub">simonrowe.dev</div>
          </div>
        </div>
        <nav className="a-sidebar__nav">
          <div className="a-sidebar__group">Workspace</div>
          {NAV.map((n) => (
            <button
              key={n.id}
              className={`a-sidebar__link ${route === n.id ? "active" : ""}`}
              onClick={() => setRoute(n.id)}
            >
              <Icon name={n.icon} size={16} />
              <span>{n.label}</span>
              {n.count != null && <span className="a-sidebar__count">{n.count}</span>}
            </button>
          ))}
          <div className="a-sidebar__group" style={{ marginTop: 24 }}>Account</div>
          <button className="a-sidebar__link"><Icon name="settings" size={16} /><span>Settings</span></button>
          <button className="a-sidebar__link"><Icon name="log-out" size={16} /><span>Sign out</span></button>
        </nav>
        <div className="a-sidebar__user">
          <div className="a-sidebar__avatar">SR</div>
          <div>
            <div className="a-sidebar__user-name">Simon Rowe</div>
            <div className="a-sidebar__user-email">simon@simonrowe.dev</div>
          </div>
          <Icon name="chevron-up" size={14} />
        </div>
      </aside>

      <div className="a-main">
        <header className="a-topbar">
          <div className="a-topbar__title">
            <h1>{title}</h1>
            {subtitle && <p>{subtitle}</p>}
          </div>
          <div className="a-topbar__actions">{actions}</div>
        </header>
        <div className="a-content">{children}</div>
      </div>
    </div>
  );
}

window.AdminLayout = AdminLayout;
