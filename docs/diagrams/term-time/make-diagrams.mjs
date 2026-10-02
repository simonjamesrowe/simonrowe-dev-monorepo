// Writes diagrams/*.json (Excalidraw element skeletons, house style applied by the renderer).
import fs from 'node:fs';

const C = { blue: '#a5d8ff', green: '#b2f2bb', purple: '#d0bfff', orange: '#ffd8a8', yellow: '#fff3bf', teal: '#c3fae8', red: '#ffc9c9', pink: '#eebefa' };
const box = (id, x, y, w, h, text, bg, extra = {}) => ({ type: 'rectangle', id, x, y, width: w, height: h, backgroundColor: bg, label: { text, fontSize: extra.fontSize ?? 20 }, ...extra.el });
const ellipse = (id, x, y, w, h, text, bg, extra = {}) => ({ type: 'ellipse', id, x, y, width: w, height: h, backgroundColor: bg, label: { text, fontSize: extra.fontSize ?? 20 }, ...extra.el });
const diamond = (id, x, y, w, h, text, bg, extra = {}) => ({ type: 'diamond', id, x, y, width: w, height: h, backgroundColor: bg, label: { text, fontSize: extra.fontSize ?? 19 }, ...extra.el });
const text = (id, x, y, t, size = 20, extra = {}) => ({ type: 'text', id, x, y, text: t, fontSize: size, ...extra });
// An arrow from point to point, bound to the shapes it joins.
const arrowEl = (id, from, to, [x1, y1], [x2, y2], extra = {}) => ({
  type: 'arrow', id, x: x1, y: y1, points: extra.points ?? [[0, 0], [x2 - x1, y2 - y1]],
  start: { id: from }, end: { id: to },
  ...(extra.dashed ? { strokeStyle: 'dashed' } : {}),
  ...(extra.group ? { groupIds: [extra.group] } : {}),
});
// An arrow plus, optionally, its label as a separate text element beside the midpoint.
const arrow = (id, from, to, p1, p2, extra = {}) => {
  const els = [arrowEl(id, from, to, p1, p2, extra)];
  if (extra.label) {
    const [mx, my] = extra.labelAt ?? [(p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2];
    const vertical = Math.abs(p2[0] - p1[0]) < 20;
    els.push(text(`${id}-label`, vertical ? mx + 10 : mx - extra.label.length * 4.5, vertical ? my - 12 : my - 30,
      extra.label, 18, { strokeColor: '#495057', ...(extra.group ? { groupIds: [extra.group] } : {}) }));
  }
  return els;
};
const g = (group) => ({ el: { groupIds: [group] } });

// 1. Sources: where the answers come from.
const sources = [
  text('title', 430, -110, 'Where the answers come from', 36),
  box('website', 0, 0, 240, 90, 'School website\npages and PDFs', C.blue, g('sources')),
  box('calendar', 0, 150, 240, 90, 'Calendar feed\niCal', C.blue, g('sources')),
  box('mailbox', 0, 300, 240, 90, 'School mailbox\nGmail, read-only', C.blue, g('sources')),
  box('notes', 0, 450, 240, 90, 'WhatsApp notes\nand photos', C.orange, g('sources')),
  box('fetch', 360, 60, 260, 100, 'Fetch and extract\ncrawler, PDF, vision', C.purple, g('ingest')),
  box('dates', 360, 260, 260, 100, 'Pull out the dates\nand year groups', C.purple, g('ingest')),
  diamond('tier', 350, 430, 280, 170, 'Public or\nrestricted?', C.yellow, g('ingest')),
  box('mongo', 760, 60, 250, 100, 'MongoDB\ndocuments, events', C.teal, g('stores')),
  box('es', 760, 260, 250, 100, 'Elasticsearch\nschool vectors', C.teal, g('stores')),
  box('queue', 760, 470, 250, 90, 'Approval queue\nadmin console', C.red, g('stores')),
  box('chat', 1150, 160, 250, 110, 'Term Time chat\ntools and search', C.green, g('answers')),
  ellipse('parent', 1170, 400, 210, 100, 'A parent', C.pink, g('answers')),
  arrow('a-website', 'website', 'fetch', [240, 45], [360, 95]),
  arrow('a-calendar', 'calendar', 'fetch', [240, 195], [360, 120]),
  arrow('a-mailbox', 'mailbox', 'fetch', [240, 345], [360, 140]),
  arrow('a-notes', 'notes', 'fetch', [240, 495], [360, 155]),
  arrow('a-fetch-dates', 'fetch', 'dates', [490, 160], [490, 260], { group: 'ingest' }),
  arrow('a-dates-tier', 'dates', 'tier', [490, 360], [490, 430], { group: 'ingest' }),
  arrow('a-dates-mongo', 'dates', 'mongo', [620, 290], [760, 120], { label: 'events', labelAt: [640, 200] }),
  arrow('a-tier-es', 'tier', 'es', [600, 480], [760, 330], { label: 'public', labelAt: [645, 395] }),
  arrow('a-tier-queue', 'tier', 'queue', [630, 515], [760, 515], { label: 'email' }),
  arrow('a-queue-es', 'queue', 'es', [885, 470], [885, 360], { label: 'approve' }),
  arrow('a-mongo-chat', 'mongo', 'chat', [1010, 110], [1150, 195]),
  arrow('a-es-chat', 'es', 'chat', [1010, 310], [1150, 240]),
  arrow('a-parent-chat', 'parent', 'chat', [1275, 400], [1275, 270], { label: 'asks' }),
];

// 2. Schedule: twenty-four hours of ingest.
const X0 = 0, PX = 50; // 50px per hour
const hx = (h) => X0 + h * PX;
const lanes = [
  ['mailbox', 'Mailbox · every 30 min', 40, C.yellow],
  ['calendar', 'Calendar · every 6 h', 160, C.green],
  ['website', 'Website · every 12 h', 280, C.blue],
  ['notes', 'Notes · any time', 400, C.orange],
];
const schedule = [text('title', 150, -110, 'A day in the life of the ingester', 36)];
for (let h = 0; h <= 24; h += 3) {
  schedule.push({ type: 'line', id: `tick-${h}`, x: hx(h), y: -20, points: [[0, 0], [0, 470]], strokeStyle: 'dotted', strokeColor: '#adb5bd', strokeWidth: 1 });
  schedule.push(text(`hour-${h}`, hx(h) - 26, 465, `${String(h).padStart(2, '0')}:00`, 18, { strokeColor: '#868e96' }));
}
for (const [id, label, y] of lanes) {
  schedule.push(text(`label-${id}`, -330, y - 12, label, 22));
  schedule.push({ type: 'line', id: `lane-${id}`, x: 0, y, points: [[0, 0], [1200, 0]], strokeColor: '#868e96', strokeWidth: 1 });
}
for (let i = 0; i < 48; i++) {
  schedule.push({ type: 'rectangle', id: `sync-${i}`, x: hx(0.1 + i * 0.5) - 5, y: 22, width: 10, height: 36, backgroundColor: C.yellow, fillStyle: 'solid', strokeWidth: 1, roundness: null });
}
schedule.push(text('sync-note', hx(13.5), 72, '48 syncs a day, one list call each', 18, { strokeColor: '#e8590c' }));
for (const h of [0.05, 6.05, 12.05, 18.05]) {
  schedule.push({ type: 'rectangle', id: `cal-${h}`, x: hx(h), y: 138, width: 22, height: 44, backgroundColor: C.green, fillStyle: 'solid', strokeWidth: 1 });
}
schedule.push(text('cal-note', hx(6.6), 112, 'one request each', 18, { strokeColor: '#2f9e44' }));
for (const [i, h] of [[0, 0.1], [1, 12.6]]) {
  schedule.push({ type: 'rectangle', id: `crawl-${i}`, x: hx(h), y: 255, width: 0.5 * PX, height: 50, backgroundColor: C.blue, strokeWidth: 2 });
}
schedule.push(text('crawl-note', hx(1), 318, 'about 30 minutes,\nten seconds a page', 18, { strokeColor: '#1971c2' }));
schedule.push({ type: 'arrow', id: 'crawl-gap', x: hx(0.6), y: 240, points: [[0, 0], [hx(12.6) - hx(0.6), 0]], strokeColor: '#1971c2' });
schedule.push(text('crawl-gap-note', hx(3.4), 210, '12 h after the last one finished', 18, { strokeColor: '#1971c2' }));
for (const [i, h] of [[0, 8.4], [1, 19.6]]) {
  schedule.push({ type: 'ellipse', id: `note-${i}`, x: hx(h) - 16, y: 384, width: 32, height: 32, backgroundColor: C.orange, fillStyle: 'solid' });
}
schedule.push(text('notes-note', hx(13.6), 352, 'pasted from WhatsApp, whenever', 18, { strokeColor: '#e8590c' }));

// 3. Newsletter: how fast it reaches the chat.
const newsletter = [
  text('title', 540, -130, 'How a newsletter reaches the chat', 36),
  ellipse('sent', 0, 0, 200, 110, 'Friday 15:03\nit is sent', C.blue),
  box('sync', 270, 10, 210, 90, 'Next mailbox sync\nwithin 30 min', C.yellow),
  diamond('school', 550, -25, 240, 160, "From the school's\nown address?", C.yellow, { fontSize: 18 }),
  diamond('portal', 890, -25, 240, 160, 'A link to its\nnewsletter page?', C.yellow, { fontSize: 18 }),
  box('fetch', 1230, 5, 230, 100, 'Fetch that page\npublic at once', C.purple),
  ellipse('ask', 1530, 0, 190, 110, 'Ask about\nit', C.green),
  box('ignored', 570, 260, 200, 80, 'Ignored', '#e9ecef'),
  box('held', 900, 260, 220, 90, 'Restricted\nuntil approved', C.red),
  arrow('n1', 'sent', 'sync', [200, 55], [270, 55]),
  arrow('n2', 'sync', 'school', [480, 55], [550, 55]),
  arrow('n3', 'school', 'portal', [790, 55], [890, 55], { label: 'yes' }),
  arrow('n4', 'portal', 'fetch', [1130, 55], [1230, 55], { label: 'yes' }),
  arrow('n5', 'fetch', 'ask', [1460, 55], [1530, 55]),
  arrow('n6', 'school', 'ignored', [670, 135], [670, 260], { label: 'no' }),
  arrow('n7', 'portal', 'held', [1010, 135], [1010, 260], { label: 'no' }),
];

// 4. Architecture: ingest on the left, the request path on the right.
const region = (id, x, y, w, h, label) => [
  { type: 'rectangle', id, x, y, width: w, height: h, strokeStyle: 'dashed', strokeColor: '#868e96', strokeWidth: 1, roughness: 1 },
  text(`${id}-label`, x + 20, y + 14, label, 22, { strokeColor: '#495057' }),
];
const architecture = [
  text('title', 470, -110, 'Inside Term Time', 36),
  ...region('ingest-region', 0, 0, 580, 700, 'Ingest, on a schedule'),
  ...region('request-region', 640, 0, 600, 700, 'A question'),
  box('scheduler', 30, 70, 400, 80, 'Scheduled jobs in the backend', C.yellow),
  box('crawler', 15, 220, 128, 90, 'Website\ncrawler', C.blue),
  box('calendar', 158, 220, 128, 90, 'Calendar\nfeed', C.blue),
  box('gmail', 301, 220, 128, 90, 'Gmail\nsync', C.blue),
  box('notes', 444, 220, 122, 90, 'Admin\nnotes', C.orange),
  box('extract', 110, 390, 360, 100, 'Event extractor and\ntier classifier, Embabel', C.purple),
  box('mongo', 30, 560, 230, 100, 'MongoDB\ndocuments, events', C.teal),
  box('es', 320, 560, 230, 100, 'Elasticsearch\nschool vectors', C.teal),
  ellipse('page', 790, 50, 300, 90, 'The Term Time page', '#e9ecef'),
  box('stomp', 800, 180, 280, 70, 'STOMP over WebSocket', C.blue),
  box('guard', 800, 290, 280, 70, 'Topic guardrail', C.red),
  box('chat', 800, 400, 280, 90, 'Chat service\nits own ChatClient', C.green),
  box('tools', 670, 560, 270, 100, 'Tools: term dates,\nevents, search', C.purple),
  box('langfuse', 990, 560, 220, 100, 'Langfuse\na trace per turn', C.pink),
  arrow('r1', 'scheduler', 'crawler', [79, 150], [79, 220]),
  arrow('r2', 'scheduler', 'calendar', [222, 150], [222, 220]),
  arrow('r3', 'scheduler', 'gmail', [365, 150], [365, 220]),
  arrow('r4', 'crawler', 'extract', [79, 310], [160, 390]),
  arrow('r5', 'calendar', 'extract', [222, 310], [250, 390]),
  arrow('r6g', 'gmail', 'extract', [365, 310], [340, 390]),
  arrow('r7n', 'notes', 'extract', [505, 310], [430, 390]),
  arrow('r6', 'extract', 'mongo', [200, 490], [145, 560]),
  arrow('r7', 'extract', 'es', [380, 490], [435, 560]),
  arrow('q1', 'page', 'stomp', [940, 140], [940, 180]),
  arrow('q2', 'stomp', 'guard', [940, 250], [940, 290]),
  arrow('q3', 'guard', 'chat', [940, 360], [940, 400], { label: 'on topic' }),
  arrow('q4', 'chat', 'tools', [880, 490], [805, 560]),
  arrow('q5', 'chat', 'langfuse', [1000, 490], [1100, 560], { dashed: true }),
  arrow('q6', 'tools', 'es', [670, 610], [550, 610]),
  arrow('q7', 'tools', 'mongo', [700, 660], [145, 660], { points: [[0, 0], [0, 50], [-555, 50], [-555, 0]] }),
];

for (const [name, nested] of Object.entries({ sources, schedule, newsletter, architecture })) {
  const els = nested.flat();
  fs.writeFileSync(`diagrams/${name}.json`, JSON.stringify(els, null, 2) + '\n');
  console.log(name, els.length, 'elements');
}
