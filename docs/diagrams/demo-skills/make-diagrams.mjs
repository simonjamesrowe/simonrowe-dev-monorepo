// Writes docs/diagrams/demo-skills/*.json (Excalidraw element skeletons; the
// demo-record renderer applies the house style). Render with export-svg.mjs.
import fs from 'node:fs';
import path from 'node:path';

const HERE = path.dirname(new URL(import.meta.url).pathname);
const C = { blue: '#a5d8ff', green: '#b2f2bb', purple: '#d0bfff', orange: '#ffd8a8', yellow: '#fff3bf', teal: '#c3fae8', red: '#ffc9c9', pink: '#eebefa' };
const box = (id, x, y, w, h, text, bg, group, fontSize = 20) => ({ type: 'rectangle', id, x, y, width: w, height: h, backgroundColor: bg, groupIds: [group], label: { text, fontSize } });
const diamond = (id, x, y, w, h, text, bg, group) => ({ type: 'diamond', id, x, y, width: w, height: h, backgroundColor: bg, groupIds: [group], label: { text, fontSize: 19 } });
const text = (id, x, y, t, size = 20, extra = {}) => ({ type: 'text', id, x, y, text: t, fontSize: size, ...extra });
const arrow = (id, from, to, [x1, y1], [x2, y2], extra = {}) => {
  const els = [{
    type: 'arrow', id, x: x1, y: y1, points: extra.points ?? [[0, 0], [x2 - x1, y2 - y1]],
    start: { id: from }, end: { id: to }, ...(extra.dashed ? { strokeStyle: 'dashed' } : {}),
  }];
  if (extra.label) {
    const [lx, ly] = extra.labelAt;
    els.push(text(`${id}-label`, lx, ly, extra.label, 18, { strokeColor: '#495057' }));
  }
  return els;
};

// Two rows: demo-plan on top, demo-record underneath, joined by the approval gate.
const W = 220, H = 100, GAP = 70, STEP = W + GAP;
const x = (i) => i * STEP;
const TOP = 0, BOTTOM = 330;

const pipeline = [
  text('title', 360, -150, 'From "I should do a demo" to an MP4', 36),
  text('plan-heading', 0, -70, 'demo-plan: the story', 24, { strokeColor: '#7048e8' }),
  box('grill', x(0), TOP, W, H, 'Grill me\naim, audience,\nthree messages', C.yellow, 'plan'),
  box('walk', x(1), TOP, W, H, 'Walk the product\nPlaywright MCP', C.blue, 'plan'),
  box('script', x(2), TOP, W, H, 'Draft script.mjs\nscenes + narration', C.purple, 'plan'),
  box('rehearse', x(3), TOP, W, H, 'Rehearse\ntime every action', C.purple, 'plan'),
  diamond('gate', x(4) - 10, TOP - 35, 240, 170, 'I approve\nthe plan table', C.red, 'plan'),
  ...arrow('a-grill-walk', 'grill', 'walk', [W, 50], [x(1), 50]),
  ...arrow('a-walk-script', 'walk', 'script', [x(1) + W, 50], [x(2), 50]),
  ...arrow('a-script-rehearse', 'script', 'rehearse', [x(2) + W, 50], [x(3), 50]),
  ...arrow('a-rehearse-gate', 'rehearse', 'gate', [x(3) + W, 50], [x(4) - 10, 50]),

  text('record-heading', W / 2 + 30, BOTTOM - 70, 'demo-record: the video', 24, { strokeColor: '#2f9e44' }),
  box('narrate', x(0), BOTTOM, W, H, 'Narrate first\nGoogle TTS,\ncached per line', C.yellow, 'record'),
  box('film', x(1), BOTTOM, W, H, 'Film\nChromium screencast\nor the real Mac app', C.blue, 'record', 19),
  box('diagrams', x(1), BOTTOM + 170, W, H, 'Excalidraw\ndiagram walkthroughs', C.purple, 'record', 19),
  box('mix', x(2), BOTTOM, W, H, 'Mix\nffmpeg, -16 LUFS', C.purple, 'record'),
  box('verify', x(3), BOTTOM, W, H, 'Verify\nevery still,\npage problems', C.red, 'record'),
  box('out', x(4), BOTTOM, W, H, 'MP4, captions,\nposter, blurb', C.green, 'record'),
  ...arrow('a-gate-narrate', 'gate', 'narrate', [x(4) + 110, TOP + 135], [W / 2, BOTTOM], {
    points: [[0, 0], [0, 75], [-(x(4) + 110 - W / 2), 75], [-(x(4) + 110 - W / 2), BOTTOM - TOP - 135]],
  }),
  ...arrow('a-narrate-film', 'narrate', 'film', [W, BOTTOM + 50], [x(1), BOTTOM + 50]),
  ...arrow('a-diagrams-film', 'diagrams', 'film', [x(1) + W / 2, BOTTOM + 170], [x(1) + W / 2, BOTTOM + H]),
  ...arrow('a-film-mix', 'film', 'mix', [x(1) + W, BOTTOM + 50], [x(2), BOTTOM + 50]),
  ...arrow('a-mix-verify', 'mix', 'verify', [x(2) + W, BOTTOM + 50], [x(3), BOTTOM + 50]),
  ...arrow('a-verify-out', 'verify', 'out', [x(3) + W, BOTTOM + 50], [x(4), BOTTOM + 50]),
  ...arrow('a-verify-script', 'verify', 'script', [x(3) + 30, BOTTOM], [x(2) + W - 30, TOP + H], {
    dashed: true, label: 'tweak a line,\nrebuild', labelAt: [x(3) - 150, TOP + H + 150],
  }),
];

fs.writeFileSync(path.join(HERE, 'pipeline.json'), `${JSON.stringify(pipeline, null, 2)}\n`);
console.log('wrote pipeline.json');
