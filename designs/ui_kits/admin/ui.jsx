/* global React */
const { useEffect } = React;

function Icon({ name, size = 18, strokeWidth = 1.7, className = "" }) {
  useEffect(() => { if (window.lucide) window.lucide.createIcons(); });
  return React.createElement("i", {
    "data-lucide": name, width: size, height: size,
    "stroke-width": strokeWidth, className,
    style: { width: size, height: size, display: "inline-flex" },
  });
}

function AButton({ variant = "primary", icon, children, onClick, size = "md" }) {
  return (
    <button className={`a-btn a-btn--${variant} a-btn--${size}`} onClick={onClick}>
      {icon && <Icon name={icon} size={14} />}
      <span>{children}</span>
    </button>
  );
}

function StatCard({ label, value, delta, deltaTone = "good", icon, accent }) {
  return (
    <div className="a-stat">
      <div className="a-stat__row">
        <div className="a-stat__label">{label}</div>
        {icon && <div className="a-stat__icon"><Icon name={icon} size={16} /></div>}
      </div>
      <div className={`a-stat__value ${accent ? "a-stat__value--accent" : ""}`}>{value}</div>
      {delta && <div className={`a-stat__delta a-stat__delta--${deltaTone}`}>{delta}</div>}
    </div>
  );
}

function Status({ kind }) {
  const map = {
    published: { label: "Published", tone: "good" },
    draft:     { label: "Draft",     tone: "neutral" },
    scheduled: { label: "Scheduled", tone: "info" },
  };
  const s = map[kind] || map.draft;
  return <span className={`a-status a-status--${s.tone}`}><span className="dot" />{s.label}</span>;
}

function SearchBar({ placeholder = "Search…", value, onChange }) {
  return (
    <div className="a-search">
      <Icon name="search" size={15} />
      <input value={value || ""} onChange={(e) => onChange?.(e.target.value)} placeholder={placeholder} />
      <kbd>⌘K</kbd>
    </div>
  );
}

Object.assign(window, { Icon, AButton, StatCard, Status, SearchBar });
