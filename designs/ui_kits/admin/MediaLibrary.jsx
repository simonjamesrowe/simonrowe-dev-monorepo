/* global React, Icon */

const ITEMS = [
  { name: "hero-bg-2026.jpg", grad: "linear-gradient(135deg, #1d3a4a, #2d4a6a)", size: "2.4 MB", w: 2880 },
  { name: "blog-ai-cover.jpg", grad: "linear-gradient(135deg, #2a1f3a, #4a2d6a)", size: "1.8 MB", w: 1920 },
  { name: "talk-qcon-2026.jpg", grad: "linear-gradient(135deg, #1c2029, #355c4a)", size: "3.1 MB", w: 2560 },
  { name: "kafka-diagram.png", grad: "linear-gradient(135deg, #2a3a4a, #4a5a6a)", size: "0.8 MB", w: 1440 },
  { name: "headshot-2026.jpg", grad: "linear-gradient(135deg, #3a2d4a, #5a4a6a)", size: "1.2 MB", w: 1600 },
  { name: "eval-graph.png", grad: "linear-gradient(135deg, #1c2029, #2a4a3a)", size: "0.4 MB", w: 1080 },
  { name: "team-photo.jpg", grad: "linear-gradient(135deg, #4a3a2d, #6a5a4a)", size: "2.7 MB", w: 2400 },
  { name: "logo-circuit.svg", grad: "linear-gradient(135deg, #1c2029, #1d3a4a)", size: "0.1 MB", w: 0 },
];

function MediaLibrary() {
  return (
    <div className="a-media">
      <div className="a-media__upload">
        <Icon name="upload-cloud" size={28} />
        <h3>Drop files to upload</h3>
        <p>or <a className="a-link">browse from your computer</a> · PNG, JPG, SVG, GIF up to 10 MB</p>
      </div>

      <div className="a-media__head">
        <h3>Library <span className="muted">216 items · 48 GB used</span></h3>
        <div className="a-media__view">
          <button className="a-iconbtn active"><Icon name="grid" size={14} /></button>
          <button className="a-iconbtn"><Icon name="list" size={14} /></button>
        </div>
      </div>

      <div className="a-media__grid">
        {ITEMS.map((m, i) => (
          <figure key={i} className="a-media__item">
            <div className="a-media__thumb" style={{ background: m.grad }}>
              {m.name.endsWith(".svg") && <Icon name="image" size={24} />}
            </div>
            <figcaption>
              <div className="a-media__name">{m.name}</div>
              <div className="a-media__meta">{m.size}{m.w > 0 && <> · {m.w}px</>}</div>
            </figcaption>
          </figure>
        ))}
      </div>
    </div>
  );
}

window.MediaLibrary = MediaLibrary;
