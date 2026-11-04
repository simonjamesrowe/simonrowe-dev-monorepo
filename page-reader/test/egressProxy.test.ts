import http from 'node:http';
import net from 'node:net';
import { afterEach, describe, expect, it } from 'vitest';
import { publicWebOnly, type DestinationPolicy } from '../src/addresses.js';
import { resolveAllowed, startEgressProxy, type EgressProxy } from '../src/egressProxy.js';

const closers: Array<() => Promise<void>> = [];

afterEach(async () => {
  while (closers.length > 0) {
    await closers.pop()?.();
  }
});

async function target(body: string): Promise<number> {
  const server = http.createServer((_request, response) => response.end(body));
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  closers.push(() => new Promise((resolve) => server.close(() => resolve())));
  return (server.address() as net.AddressInfo).port;
}

async function proxy(policy: DestinationPolicy, refused: string[] = []): Promise<EgressProxy> {
  const started = await startEgressProxy(policy, (detail) => refused.push(detail));
  closers.push(() => started.close());
  return started;
}

/** Sends a CONNECT and returns the proxy's status line. */
function connectThrough(proxyPort: number, authority: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const socket = net.connect(proxyPort, '127.0.0.1', () => {
      socket.write(`CONNECT ${authority} HTTP/1.1\r\nHost: ${authority}\r\n\r\n`);
    });
    socket.once('data', (chunk) => {
      resolve(chunk.toString('latin1').split('\r\n')[0] ?? '');
      socket.destroy();
    });
    socket.on('error', reject);
  });
}

/** Sends an absolute-form plain HTTP request and returns status and body. */
function getThrough(proxyPort: number, url: string): Promise<{ status: number; body: string }> {
  return new Promise((resolve, reject) => {
    const request = http.request({ host: '127.0.0.1', port: proxyPort, path: url, method: 'GET' }, (response) => {
      let body = '';
      response.on('data', (chunk) => (body += chunk));
      response.on('end', () => resolve({ status: response.statusCode ?? 0, body }));
    });
    request.on('error', reject);
    request.end();
  });
}

describe('egress proxy, production policy', () => {
  it('refuses a CONNECT to loopback, and says why', async () => {
    const port = await target('INTERNAL SECRET');
    const refused: string[] = [];
    const egress = await proxy(publicWebOnly, refused);

    expect(await connectThrough(egress.port, `127.0.0.1:${port}`)).toContain('403');
    expect(refused[0]).toContain('127.0.0.1');
  });

  it('refuses a name that resolves to loopback', async () => {
    const port = await target('INTERNAL SECRET');
    const egress = await proxy(publicWebOnly);

    expect(await connectThrough(egress.port, `localhost:${port}`)).toContain('403');
  });

  it('refuses plain HTTP to loopback without reaching it', async () => {
    const port = await target('INTERNAL SECRET');
    const egress = await proxy(publicWebOnly);

    const answer = await getThrough(egress.port, `http://127.0.0.1:${port}/`);

    expect(answer.status).toBe(403);
    expect(answer.body).not.toContain('INTERNAL SECRET');
  });

  it('refuses a malformed CONNECT authority', async () => {
    const egress = await proxy(publicWebOnly);

    expect(await connectThrough(egress.port, 'no-port-here')).toContain('400');
  });
});

describe('egress proxy, permissive policy', () => {
  it('tunnels a CONNECT the policy allows', async () => {
    const port = await target('hello');
    const egress = await proxy(() => true);

    expect(await connectThrough(egress.port, `127.0.0.1:${port}`)).toContain('200');
  });

  it('forwards plain HTTP the policy allows', async () => {
    const port = await target('hello');
    const egress = await proxy(() => true);

    expect(await getThrough(egress.port, `http://127.0.0.1:${port}/page`)).toEqual({ status: 200, body: 'hello' });
  });
});

describe('resolveAllowed', () => {
  it('needs every resolved address to pass, not just one', async () => {
    const mixed = async () => ['93.184.215.14', '10.0.0.7'];

    await expect(resolveAllowed('mixed.example', 443, publicWebOnly, mixed)).rejects.toThrow('10.0.0.7');
    await expect(resolveAllowed('public.example', 443, publicWebOnly, async () => ['93.184.215.14']))
      .resolves.toBe('93.184.215.14');
  });

  it('refuses a name that resolves to nothing', async () => {
    await expect(resolveAllowed('nowhere.example', 443, () => true, async () => [])).rejects.toThrow('no address');
  });

  it('accepts a bracketed IPv6 literal', async () => {
    await expect(resolveAllowed('[::1]', 80, () => true)).resolves.toBe('::1');
  });
});
