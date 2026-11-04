import http from 'node:http';
import type net from 'node:net';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { startEgressProxy, type EgressProxy } from '../src/egressProxy.js';
import { createReader, ReadOverran, UnreadableUrl, userAgentFor, type ReadPageResult } from '../src/readPage.js';

/**
 * A real headless Chromium behind a real egress proxy, against two local servers: the
 * "publisher", which the test policy allows, and an "internal" one, which it does not. The
 * policy stands in for production's public-addresses-only rule, which would refuse both.
 */
const ARTICLE = `<!doctype html><html><head><title>Scaling Git at Example</title></head><body>
  <nav>Home · Research · Company</nav>
  <article><h1>Scaling Git at Example</h1>
    <p>Over the past year, the size and demands on our monorepo have increased at a staggering pace.
       It was clear that we needed to revamp our source control system.</p>
    <p>We built a new backend for Git repositories without interrupting any engineering work or
       changing how our team uses Git. It now powers all development in the monorepo.</p>
    <p>In production it cut median push latency by nearly five times, and it now delivers close
       to a petabyte of Git data per day.</p>
  </article><footer>Cookie settings</footer></body></html>`;

let publisher: http.Server;
let internal: http.Server;
let publisherPort: number;
let internalPort: number;
let egress: EgressProxy;
let seenUserAgent = '';
const refused: string[] = [];

async function listen(server: http.Server): Promise<number> {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  return (server.address() as net.AddressInfo).port;
}

beforeAll(async () => {
  internal = http.createServer((_request, response) => response.end('<html><body>INTERNAL SECRET</body></html>'));
  internalPort = await listen(internal);
  publisher = http.createServer((request, response) => {
    seenUserAgent = String(request.headers['user-agent'] ?? '');
    switch (request.url) {
      case '/article':
        response.writeHead(200, { 'Content-Type': 'text/html' }).end(ARTICLE);
        return;
      case '/challenge':
        response.writeHead(403, { 'Content-Type': 'text/html' })
          .end('<html><head><title>Just a moment...</title></head><body>Checking</body></html>');
        return;
      case '/missing':
        response.writeHead(404, { 'Content-Type': 'text/html' })
          .end('<html><head><title>Not found</title></head><body>Gone</body></html>');
        return;
      case '/redirect-inside':
        response.writeHead(302, { Location: `http://127.0.0.1:${internalPort}/secret` }).end();
        return;
      default:
        response.writeHead(500).end();
    }
  });
  publisherPort = await listen(publisher);
  egress = await startEgressProxy(
    (address, port) => address === '127.0.0.1' && port === publisherPort,
    (detail) => refused.push(detail),
  );
});

afterAll(async () => {
  await egress?.close();
  publisher?.close();
  internal?.close();
});

const read = (path: string): Promise<ReadPageResult> =>
  createReader({ proxyPort: egress.port, challengeWaitMs: 1_500, navigationTimeoutMs: 15_000 })({
    url: `http://127.0.0.1:${publisherPort}${path}`,
  });

describe('readPage in a real browser', () => {
  it('returns the article body, without the page chrome, as an ordinary Chrome', async () => {
    const result = await read('/article');

    expect(result.outcome).toBe('READ');
    expect(result.httpStatus).toBe(200);
    expect(result.title).toBe('Scaling Git at Example');
    expect(result.text).toContain('increased at a staggering pace');
    expect(result.text).toContain('close to a petabyte of Git data per day');
    expect(result.text).not.toContain('Cookie settings');
    expect(seenUserAgent).toMatch(/ Chrome\/\d+\.0\.0\.0 /);
    expect(seenUserAgent).not.toContain('Headless');
  }, 60_000);

  it('reports a challenge that never clears as BLOCKED', async () => {
    const result = await read('/challenge');

    expect(result).toMatchObject({ outcome: 'BLOCKED', httpStatus: 403, text: null });
  }, 60_000);

  it('reports an HTTP error page as REFUSED', async () => {
    expect(await read('/missing')).toMatchObject({ outcome: 'REFUSED', httpStatus: 404, text: null });
  }, 60_000);

  /** The reason the proxy exists: interception inside the browser never sees this hop. */
  it('refuses a redirect to an address the policy forbids, and never reads it', async () => {
    const result = await read('/redirect-inside');

    expect(result.outcome).toBe('REFUSED');
    expect(result.text ?? '').not.toContain('INTERNAL SECRET');
    expect(refused.some((detail) => detail.includes(`127.0.0.1:${internalPort}`))).toBe(true);
  }, 60_000);

  /** Chromium sends loopback straight out unless told otherwise; `<-loopback>` is that. */
  it('sends even a loopback address through the proxy', async () => {
    const result = await createReader({ proxyPort: egress.port, navigationTimeoutMs: 15_000 })({
      url: `http://127.0.0.1:${internalPort}/secret`,
    });

    expect(result.outcome).toBe('REFUSED');
    expect(result.text ?? '').not.toContain('INTERNAL SECRET');
  }, 60_000);

  it('reports a port Chromium refuses as REFUSED rather than retrying it', async () => {
    const result = await createReader({ proxyPort: egress.port, navigationTimeoutMs: 15_000 })({
      url: 'http://127.0.0.1:22/',
    });

    expect(result.outcome).toBe('REFUSED');
  }, 60_000);

  it('closes the browser and throws once a read outlives its deadline', async () => {
    const reader = createReader({
      proxyPort: egress.port,
      readDeadlineMs: 1_000,
      challengeWaitMs: 10_000,
    });

    await expect(reader({ url: `http://127.0.0.1:${publisherPort}/challenge` })).rejects.toBeInstanceOf(ReadOverran);
  }, 60_000);

  it('rejects an address that is not an http(s) URL before launching anything', async () => {
    const reader = createReader({ proxyPort: egress.port });

    await expect(reader({ url: 'file:///etc/passwd' })).rejects.toBeInstanceOf(UnreadableUrl);
    await expect(reader({ url: 'not a url' })).rejects.toBeInstanceOf(UnreadableUrl);
  });
});

describe('userAgentFor', () => {
  it('names an ordinary Chrome of the same major version', () => {
    expect(userAgentFor('141.0.7390.37')).toBe(
      'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36',
    );
  });
});
