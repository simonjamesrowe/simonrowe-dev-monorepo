/* global React */
const { useEffect } = React;

/* tiny helper to render a Lucide icon by name without imports */
function Icon({ name, size = 18, className = "", strokeWidth = 1.7 }) {
  useEffect(() => {
    if (window.lucide) window.lucide.createIcons();
  });
  return React.createElement("i", {
    "data-lucide": name,
    width: size,
    height: size,
    "stroke-width": strokeWidth,
    className,
    style: { width: size, height: size, display: "inline-flex" },
  });
}

function Button({ variant = "primary", children, onClick, icon, iconRight }) {
  const cls = `sr-btn sr-btn--${variant}`;
  return (
    <button className={cls} onClick={onClick}>
      {icon && <Icon name={icon} size={15} />}
      <span>{children}</span>
      {iconRight && <Icon name={iconRight} size={15} />}
    </button>
  );
}

function Pill({ children, tone = "neutral" }) {
  return <span className={`sr-pill sr-pill--${tone}`}>{children}</span>;
}

function IconBtn({ name, onClick, label }) {
  return (
    <button className="sr-iconbtn" onClick={onClick} aria-label={label}>
      <Icon name={name} size={16} />
    </button>
  );
}

function Eyebrow({ children }) {
  return <div className="sr-eyebrow">{children}</div>;
}

function Section({ id, eyebrow, title, accent, sub, children }) {
  return (
    <section className="sr-section" id={id}>
      <div className="sr-section__head">
        {eyebrow && <Eyebrow>{eyebrow}</Eyebrow>}
        <h2 className="sr-section__title">
          {title} {accent && <span className="sr-accent">{accent}</span>}
        </h2>
        {sub && <p className="sr-section__sub">{sub}</p>}
      </div>
      {children}
    </section>
  );
}

Object.assign(window, { Icon, Button, Pill, IconBtn, Eyebrow, Section });
