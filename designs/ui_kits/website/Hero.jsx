/* global React, Button, Icon */

function Hero({ onChat }) {
  return (
    <section className="sr-hero">
      <div className="sr-hero__bg" />
      <div className="sr-hero__overlay" />
      <div className="sr-hero__inner">
        <div className="sr-eyebrow sr-hero__eyebrow">
          Engineering Leadership <span className="sr-slash">//</span> AI-Native Systems
        </div>
        <h1 className="sr-hero__title">
          Simon <span className="sr-accent">Rowe</span>
        </h1>
        <p className="sr-hero__role">
          Head of Engineering, Commercial Trading at Global
        </p>
        <p className="sr-hero__tag">
          Passionate about AI-native development, cloud-native architecture,
          and empowering engineering teams to ship real business value.
        </p>
        <div className="sr-hero__ctas">
          <Button variant="primary" iconRight="arrow-right" onClick={onChat}>
            Let's connect
          </Button>
          <Button variant="ghost" icon="download">
            Download CV
          </Button>
        </div>
        <div className="sr-hero__scroll">
          <Icon name="arrow-down" size={14} /> Scroll
        </div>
      </div>
    </section>
  );
}

window.Hero = Hero;
