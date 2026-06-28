# Admin console UI kit

A click-thru recreation of the authenticated admin console — the CMS surface
for managing blog posts, events, and experience entries.

Open `index.html`. The default view is the dashboard; the sidebar links route
between Dashboard, Blog, Events, Experience, and Media.

## Components
- `AdminLayout.jsx` — sidebar + topbar shell
- `Dashboard.jsx` — stat cards + recent activity
- `BlogList.jsx` — table of posts with status, draft/publish actions
- `BlogEditor.jsx` — split-pane MDX-style editor (visual recreation only)
- `EventList.jsx` — upcoming + past events
- `MediaLibrary.jsx` — image grid with upload zone
- `ui.jsx` — shared admin bits (Table, StatCard, Tabs, etc.)
