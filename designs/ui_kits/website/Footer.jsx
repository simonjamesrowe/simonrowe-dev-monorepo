/* global React, Icon */

function Footer() {
  return (
    <footer className="sr-footer">
      <div className="sr-footer__inner">
        <div className="sr-footer__brand">
          <a className="sr-brand"><span>simon</span><span className="sr-accent">.</span><span>rowe</span></a>
          <p>Engineering leadership, AI-native delivery, and the occasional blog post.</p>
          <div className="sr-footer__social">
            {["github", "linkedin", "twitter", "rss"].map((s) => (
              <a key={s} className="sr-iconbtn" aria-label={s}><Icon name={s} size={15} /></a>
            ))}
          </div>
        </div>
        <div className="sr-footer__col">
          <h5>Site</h5>
          <a>Home</a><a>Experience</a><a>Blog</a><a>Events</a><a>Contact</a>
        </div>
        <div className="sr-footer__col">
          <h5>Connect</h5>
          <a>hello@simonrowe.dev</a>
          <a>Download CV</a>
          <a>Speaking</a>
        </div>
      </div>
      <div className="sr-footer__base">
        <span>© 2026 Simon Rowe</span>
        <span>Built with React, Spring Boot, Kafka — and a lot of evals.</span>
      </div>
    </footer>
  );
}

window.Footer = Footer;
