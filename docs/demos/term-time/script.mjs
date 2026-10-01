// Term Time: a narrated walkthrough for the portfolio page.
// Chat scenes run read-only against production; admin scenes run against the local stack
// (restored production data), because saving a note there has real effects.
const TERM_TIME = 'https://term-time.simonrowe.dev/';
const ADMIN = 'http://localhost:5173/admin/school';

// Email subjects, bodies and link text are blurred on the approvals screen. Most of it is public
// anyway, but restricted mail is restricted for a reason, and a video is forever.
const BLUR_RESTRICTED = `
  .admin-approval__title, .admin-approval__reason, .admin-approval__preview,
  .school-admin__link-text, .school-admin__link-url, .school-admin__search { filter: blur(7px); }
`;

const HIDE_RECENT_NOTES = '.school-notes__recent { display: none !important; }';

const NOTE = 'Hilltop Academy open evening, Thursday 15 October, 6pm to 8pm. '
  + 'Year 6 families welcome, no need to book.';

/** An answer is finished when the typing indicator has gone and a source link has rendered. */
async function waitForAnswer(page) {
  await page.waitForTimeout(1200);
  await page.locator('.chat-typing-indicator').waitFor({ state: 'detached', timeout: 90000 });
  await page.locator('.chat-message').last().getByRole('link').first().waitFor({ timeout: 30000 });
}

export default {
  title: 'Term Time — school life, one question away',
  url: TERM_TIME,
  link: 'simonrowe.dev/portfolio/term-time',
  viewport: { width: 1440, height: 810 },
  deviceScaleFactor: 2,
  contextOptions: { locale: 'en-GB', timezoneId: 'Europe/London' },
  colorScheme: 'light',
  storageState: 'auth.json',
  pronounce: { INSET: 'inset', PDFs: 'P D Fs' },
  ignoreProblems: ['google-analytics', 'googletagmanager', 'doubleclick'],
  poster: 'year-six-answer',
  setup: async ({ page }) => {
    // Start from a fresh visitor: no saved year groups, no transcript.
    await page.evaluate(() => window.localStorage.removeItem('term-time-year-groups'));
    await page.reload();
    await page.getByRole('textbox', { name: 'Type a message...' }).waitFor();
  },

  scenes: [
    {
      id: 'hook',
      show: 'Term Time, empty, with its suggested questions',
      say: 'A primary school sends a lot. Newsletters, letters, calendar changes and class emails, '
        + "plus whatever turns up in the parents' WhatsApp group. Term Time reads all of it, so I can just ask.",
      do: async ({ page, cursor }) => {
        await cursor.move(page.getByRole('button', { name: 'When is half term?' }));
      },
    },
    {
      id: 'half-term',
      show: 'Asks "When is half term?"; tool steps appear, then the answer',
      say: "When is half term? It checks today's date, then looks the term dates up in the school's own calendar feed.",
      do: async ({ page, cursor }) => {
        await cursor.click(page.getByRole('button', { name: 'When is half term?' }));
        await waitForAnswer(page);
      },
    },
    {
      id: 'source',
      show: 'Highlights the answer\'s "School calendar" link',
      say: 'Every answer carries its source. That link opens the calendar entry it came from, so I can check it in one tap.',
      do: async ({ page, cursor, highlight }) => {
        const link = page.locator('.chat-message').last().getByRole('link').first();
        await cursor.move(link);
        await highlight(link, 2.5);
      },
    },
    {
      id: 'year-six',
      show: 'Ticks Year 6, asks "When is the next school trip?"',
      say: 'With a child in Year 6, I tick Year 6 and ask about the next trip. The years I pick go '
        + "with every question, so the search looks at that year's letters first.",
      do: async ({ page, cursor }) => {
        await cursor.click(page.getByText('Year 6', { exact: true }));
        await cursor.type(page.getByRole('textbox', { name: 'Type a message...' }), 'When is the next school trip?');
        await page.keyboard.press('Enter');
        await waitForAnswer(page);
      },
    },
    {
      id: 'year-six-answer',
      show: 'The Year 6 residential answer; highlights the trip letter link',
      say: "Now Year 6's own letters come first. The residential in Kent, the times, what to pack, "
        + 'and a link to the letter that says so.',
      do: async ({ page, cursor, highlight, scrollTo }) => {
        const answer = page.locator('.chat-message').last();
        await scrollTo(answer);
        const link = answer.getByRole('link').first();
        await cursor.move(link);
        await highlight(link, 2.5);
      },
    },
    {
      id: 'notes',
      show: 'Cut to the admin console: Paste a note',
      order: 'do-then-say',
      say: "Some of it never comes from the school at all. Open evenings arrive in the parents' WhatsApp group, "
        + 'so the admin console has a Paste a note screen.',
      do: async ({ page }) => {
        await page.goto(`${ADMIN}/notes`);
        await page.addStyleTag({ content: HIDE_RECENT_NOTES });
        await page.getByLabel('The messages').waitFor();
      },
    },
    {
      id: 'paste',
      show: 'Pastes an open-evening message for Year 6 and saves it',
      say: 'I paste the message as it arrived, and save it.',
      do: async ({ page, cursor }) => {
        // Earlier notes carry real parents' names from the group chat; they stay off camera.
        await page.addStyleTag({ content: HIDE_RECENT_NOTES });
        await cursor.type(page.getByLabel('The messages'), NOTE, { delay: 18 });
        await cursor.click(page.getByRole('button', { name: 'Save note' }));
      },
    },
    {
      id: 'paste-wait',
      show: '"Reading the dates…" while the note is saved',
      say: "Term Time reads the dates out of the text and fetches any links, so a school's own page "
        + 'can fill in the time and the booking address. It is the same extraction that reads '
        + 'the newsletters, and it has to copy dates and links exactly rather than guess them. '
        + 'A note is public straight away, because pasting it is the decision.',
      do: async ({ page, scrollTo }) => {
        // Scoped to the note just saved, by its text and its "Saved" label.
        const saved = page.locator('.school-notes__result').filter({ hasText: 'Hilltop Academy' })
          .filter({ has: page.getByText('Saved', { exact: true }) });
        await saved.getByText(/dates? found/).waitFor({ timeout: 90000 });
        await scrollTo(saved);
      },
    },
    {
      id: 'paste-result',
      show: 'The saved note: one date found, narrowed to Year 6',
      say: "Here's the open evening it found, with its time, and narrowed to Year 6.",
      do: async ({ page, cursor }) => {
        await cursor.move(page.locator('.school-notes__events li').first());
      },
    },
    {
      id: 'photo',
      show: 'Chooses a photo of a spelling list; its text appears in the box',
      say: "A photographed letter works too. It's read into text I can check before saving, "
        + 'and the photo itself is never kept.',
      do: async ({ page, cursor, scrollTo }) => {
        await page.addStyleTag({ content: HIDE_RECENT_NOTES });
        await scrollTo(page.getByRole('button', { name: 'Choose a photo', exact: true }));
        await cursor.move(page.getByRole('button', { name: 'Choose a photo', exact: true }));
        await page.getByLabel('Choose a photo to transcribe').setInputFiles(new URL('./assets/letter.jpg', import.meta.url).pathname);
        await page.waitForFunction(
          () => document.querySelector('#note-text')?.value.toLowerCase().includes('because'),
          null, { timeout: 90000 });
        await cursor.move(page.getByLabel('The messages'));
      },
    },
    {
      id: 'approvals',
      show: 'The approvals queue, email subjects and bodies blurred',
      order: 'do-then-say',
      say: "School email is different. It's read every thirty minutes, but it starts out restricted, "
        + 'and nothing reaches the public chat until I approve it.',
      do: async ({ page }) => {
        await page.goto(`${ADMIN}/approvals`);
        await page.addStyleTag({ content: BLUR_RESTRICTED });
        await page.locator('.admin-approval').first().waitFor({ timeout: 30000 });
      },
    },
    {
      id: 'diagram',
      show: 'Diagram: where the answers come from, whole view',
      say: "Here's how it fits together.",
      do: async ({ diagram }) => { await diagram.show('sources'); },
    },
    {
      id: 'diagram-sources',
      show: 'Focus: the four sources',
      say: 'Four sources: the website and its PDFs, the calendar feed, the school mailbox, and those notes.',
      do: async ({ diagram }) => { await diagram.focus('sources'); },
    },
    {
      id: 'diagram-ingest',
      show: 'Focus: fetch, dates and the public-or-restricted decision',
      say: 'Each one is fetched, its dates and year groups are pulled out, and email is held for approval.',
      do: async ({ diagram }) => { await diagram.focus('ingest'); },
    },
    {
      id: 'diagram-answers',
      show: 'Focus: the stores and the chat',
      say: 'And the chat answers from what is stored, using tools that look dates up rather than guessing.',
      do: async ({ diagram }) => { await diagram.focus(['stores', 'answers']); },
    },
  ],

  outro: 'That was Term Time, a school assistant that answers from what the school actually published. '
    + 'Every answer carries its source, so parents can check it in one tap.',
};
