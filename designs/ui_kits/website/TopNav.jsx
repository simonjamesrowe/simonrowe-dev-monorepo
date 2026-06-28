/* global React, Icon, Button, IconBtn */
const { useState, useEffect } = React;

function TopNav({ onChat, theme, setTheme }) {
  const [scrolled, setScrolled] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 20);
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);
  const links = ["Home", "Experience", "Blog", "Events", "Contact"];
  return (
    <header className={`sr-nav ${scrolled ? "sr-nav--scrolled" : ""}`}>
      <div className="sr-nav__inner">
        <a className="sr-brand" href="#">
          <span>simon</span><span className="sr-accent">.</span><span>rowe</span>
        </a>
        <nav className="sr-nav__links">
          {links.map((l, i) => (
            <a key={l} className={i === 0 ? "active" : ""}>{l}</a>
          ))}
        </nav>
        <div className="sr-nav__actions">
          <IconBtn
            name={theme === "dark" ? "sun" : "moon"}
            label="Toggle theme"
            onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          />
          <Button variant="primary" icon="message-circle" onClick={onChat}>
            Let's chat
          </Button>
          <button className="sr-nav__burger" onClick={() => setMobileOpen(!mobileOpen)}>
            <Icon name={mobileOpen ? "x" : "menu"} size={20} />
          </button>
        </div>
      </div>
      {mobileOpen && (
        <div className="sr-nav__mobile">
          {links.map((l, i) => <a key={l} className={i === 0 ? "active" : ""}>{l}</a>)}
        </div>
      )}
    </header>
  );
}

window.TopNav = TopNav;
