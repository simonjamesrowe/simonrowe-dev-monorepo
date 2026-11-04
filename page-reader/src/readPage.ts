import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { chromium, type Browser, type Page } from 'playwright-core';

/** What the factory's workflow sends. */
export interface ReadPageInput {
  url: string;
}

/**
 * How a read ended, when it ended in an answer rather than an error.
 *
 * - READ: the page's article text.
 * - BLOCKED: a bot challenge was still showing when the wait ran out.
 * - REFUSED: the publisher answered with an HTTP error status.
 * - EMPTY: the page loaded but had no readable text.
 *
 * None of these is retried: the same page would answer the same way a minute later. A
 * navigation timeout or a browser crash is thrown instead, and Temporal retries that.
 */
export type ReadOutcome = 'READ' | 'BLOCKED' | 'REFUSED' | 'EMPTY';

/** What the activity returns. Field names are the Java side's record components. */
export interface ReadPageResult {
  outcome: ReadOutcome;
  httpStatus: number | null;
  finalUrl: string | null;
  title: string | null;
  text: string | null;
}

/** Thrown for an address that is not an article URL; never worth retrying. */
export class UnreadableUrl extends Error {}

/** Thrown when a read outlives its deadline. Retryable: a slow page may answer next time. */
export class ReadOverran extends Error {}

/** The most text one read returns. The backend keeps 12,000 characters of it. */
export const MAX_TEXT_CHARS = 100_000;

/** Navigation errors that mean "not reachable from here", never "try again". */
const REFUSED_BY_NETWORK = /ERR_TUNNEL_CONNECTION_FAILED|ERR_PROXY_CONNECTION_FAILED|ERR_UNSAFE_PORT|ERR_BLOCKED_BY_CLIENT/;

/** The interstitial titles Cloudflare and its peers show while a challenge runs. */
const CHALLENGE_TITLE = /just a moment|attention required|verify you are human|checking your browser|one more step/i;

export interface ReaderOptions {
  /** The egress proxy every browser connection goes through. */
  proxyPort: number;
  navigationTimeoutMs?: number;
  /** How long a bot challenge may take to clear itself. openai.com's needed none. */
  challengeWaitMs?: number;
  /**
   * The most one read may take, browser launch to close. Past it the browser is closed, which
   * fails whatever it was waiting on, so no page can hold the worker's only slot.
   */
  readDeadlineMs?: number;
  /** Called while waiting, so Temporal knows the activity is alive. */
  heartbeat?: () => void;
  /** Overridable so tests can point at a browser of their own. */
  launch?: (proxyPort: number) => Promise<Browser>;
}

const READABILITY_SOURCE = readFileSync(
  createRequire(import.meta.url).resolve('@mozilla/readability/Readability.js'),
  'utf8',
);

/**
 * Launches Chromium with every connection sent to the egress proxy.
 *
 * - `bypass: '<-loopback>'` removes Chromium's built-in rule that loopback addresses skip the
 *   proxy, so even `http://127.0.0.1/` is checked there and refused. Playwright adds the same
 *   rule itself, but only while `PLAYWRIGHT_DISABLE_FORCED_CHROMIUM_PROXIED_LOOPBACK` is unset;
 *   stating it here keeps the control independent of the environment. Letting loopback bypass
 *   the proxy fails two tests in `readPage.test.ts`, which read the internal page.
 * - QUIC is off because it is UDP and would not go through an HTTP proxy, and WebRTC may use
 *   only proxied UDP (none), for the same reason.
 */
export function launchBehindProxy(proxyPort: number): Promise<Browser> {
  return chromium.launch({
    headless: true,
    proxy: { server: `http://127.0.0.1:${proxyPort}`, bypass: '<-loopback>' },
    args: [
      '--disable-quic',
      '--disable-dev-shm-usage',
      '--force-webrtc-ip-handling-policy=disable_non_proxied_udp',
      '--webrtc-ip-handling-policy=disable_non_proxied_udp',
    ],
  });
}

/**
 * The user agent a real Chrome of this version sends on Linux.
 *
 * <p>This is the whole of what gets past openai.com's challenge. Headless Chromium says
 * `HeadlessChrome/<version>`, and Cloudflare answers that with a challenge that never clears;
 * the same browser saying `Chrome/<version>` loads the article first time (checked
 * 2026-11-04). Masking `navigator.webdriver` made no difference. Built from the running
 * browser's own version so the two never disagree.
 */
export function userAgentFor(browserVersion: string): string {
  const major = browserVersion.split('.')[0] ?? '0';
  return `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

export function assertReadable(url: string): URL {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    throw new UnreadableUrl(`not a URL: ${url}`);
  }
  if ((parsed.protocol !== 'https:' && parsed.protocol !== 'http:') || parsed.hostname === '') {
    throw new UnreadableUrl(`not an http(s) URL with a host: ${url}`);
  }
  return parsed;
}

async function titleOf(page: Page): Promise<string> {
  try {
    return await page.title();
  } catch {
    // The page is navigating, usually because the challenge just cleared.
    return '';
  }
}

function tidy(text: string): string {
  return text.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim().slice(0, MAX_TEXT_CHARS);
}

export function createReader(options: ReaderOptions): (input: ReadPageInput) => Promise<ReadPageResult> {
  const navigationTimeoutMs = options.navigationTimeoutMs ?? 45_000;
  const challengeWaitMs = options.challengeWaitMs ?? 20_000;
  const heartbeat = options.heartbeat ?? (() => {});
  const launch = options.launch ?? launchBehindProxy;
  const readDeadlineMs = options.readDeadlineMs ?? 90_000;

  return async ({ url }: ReadPageInput): Promise<ReadPageResult> => {
    assertReadable(url);
    const browser = await launch(options.proxyPort);
    let overran = false;
    const deadline = setTimeout(() => {
      overran = true;
      void browser.close();
    }, readDeadlineMs);
    try {
      const context = await browser.newContext({
        userAgent: userAgentFor(browser.version()),
        // Readability is injected as a script tag, which a page's CSP would otherwise block.
        bypassCSP: true,
        serviceWorkers: 'block',
        acceptDownloads: false,
        viewport: { width: 1280, height: 900 },
        locale: 'en-GB',
      });
      // Not a security control (that is the proxy): images, media and fonts are dropped only
      // because nothing here reads them and the Pi pays for every byte.
      await context.route('**/*', (route) => {
        const type = route.request().resourceType();
        return type === 'image' || type === 'media' || type === 'font'
          ? route.abort('blockedbyclient')
          : route.continue();
      });
      const page = await context.newPage();
      let documentStatus: number | null = null;
      page.on('response', (response) => {
        if (response.request().isNavigationRequest() && response.frame() === page.mainFrame()) {
          documentStatus = response.status();
        }
      });

      try {
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: navigationTimeoutMs });
      } catch (error) {
        // The egress proxy refused a destination (the page, or a redirect it led to) or could
        // not connect to it, or Chromium itself refused the port. None of these improves on a
        // retry, so it is an answer, not an error. A timeout is not in this list: that one is
        // thrown, and retried.
        if (error instanceof Error && REFUSED_BY_NETWORK.test(error.message)) {
          return { outcome: 'REFUSED', httpStatus: null, finalUrl: null, title: null, text: null };
        }
        throw error;
      }
      const challengeDeadline = Date.now() + challengeWaitMs;
      let title = await titleOf(page);
      // An empty title is waited out only while no document has answered yet (the challenge is
      // mid-navigation); an error page with no title is an answer, not something to wait for.
      const stillChallenged = () =>
        CHALLENGE_TITLE.test(title) || (title === '' && documentStatus === null);
      while (stillChallenged() && Date.now() < challengeDeadline) {
        heartbeat();
        await page.waitForTimeout(1_000);
        title = await titleOf(page);
      }
      heartbeat();
      if (CHALLENGE_TITLE.test(title)) {
        return { outcome: 'BLOCKED', httpStatus: documentStatus, finalUrl: page.url(), title, text: null };
      }
      if (documentStatus !== null && documentStatus >= 400) {
        return { outcome: 'REFUSED', httpStatus: documentStatus, finalUrl: page.url(), title, text: null };
      }
      await page.waitForLoadState('load', { timeout: 10_000 }).catch(() => {});

      await page.addScriptTag({ content: READABILITY_SOURCE });
      const extracted = await page.evaluate(() => {
        const ReadabilityClass = (window as unknown as { Readability: new (doc: Document) => { parse(): { title?: string; content?: string } | null } }).Readability;
        const article = new ReadabilityClass(document.cloneNode(true) as Document).parse();
        if (article?.content) {
          // innerText needs layout, which a detached node has none of, so the article replaces
          // the live body: paragraph breaks come out as newlines, which textContent loses.
          document.body.innerHTML = article.content;
          return { title: article.title ?? document.title, text: document.body.innerText };
        }
        const root = document.querySelector('article, main') ?? document.body;
        return { title: document.title, text: (root as HTMLElement).innerText };
      });
      const text = tidy(extracted.text ?? '');
      if (text === '') {
        return { outcome: 'EMPTY', httpStatus: documentStatus, finalUrl: page.url(), title: extracted.title, text: null };
      }
      return { outcome: 'READ', httpStatus: documentStatus, finalUrl: page.url(), title: extracted.title, text };
    } catch (error) {
      if (overran) {
        throw new ReadOverran(`read of ${url} took over ${readDeadlineMs}ms`);
      }
      throw error;
    } finally {
      clearTimeout(deadline);
      await browser.close();
    }
  };
}
