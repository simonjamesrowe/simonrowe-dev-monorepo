import { lookup } from 'node:dns/promises';
import http from 'node:http';
import net from 'node:net';
import type { Duplex } from 'node:stream';
import type { DestinationPolicy } from './addresses.js';

/**
 * The only way out of the browser: an HTTP proxy on loopback that every connection the browser
 * makes goes through, and that refuses any destination the policy does not allow.
 *
 * <p>This is the control that keeps a page from reaching anything inside the stack, and it has
 * to live outside the browser. Playwright's request interception sees only the first request of
 * a navigation, not its redirect hops (checked with a 302 to a second local server), so a page
 * that redirects to {@code http://backend:8080/} would pass any in-page check. Here the check
 * runs at connect time, on every connection, after the name is resolved, and the connection is
 * made to the address that was checked, so a name that resolves differently a second time
 * (DNS rebinding) gains nothing.
 *
 * <p>Handles {@code CONNECT} (https and websockets) and absolute-form plain HTTP requests, which
 * is everything Chromium sends to an HTTP proxy.
 */
export interface EgressProxy {
  readonly port: number;
  close(): Promise<void>;
}

/** Why a destination was refused, for the log. */
export class RefusedDestination extends Error {}

/** Every address a name resolves to. */
export type ResolveAll = (host: string) => Promise<string[]>;

const systemResolver: ResolveAll = async (host) =>
  (await lookup(host, { all: true, verbatim: true })).map((entry) => entry.address);

/** Resolves a host to the one address the connection will use, if the policy allows it. */
export async function resolveAllowed(
  host: string,
  port: number,
  policy: DestinationPolicy,
  resolveAll: ResolveAll = systemResolver,
): Promise<string> {
  const bare = host.startsWith('[') && host.endsWith(']') ? host.slice(1, -1) : host;
  const addresses = net.isIP(bare) ? [bare] : await resolveAll(bare);
  // Every address must pass, not just the first: a name with one public and one private
  // address would otherwise reach the private one whenever the resolver reordered them.
  if (addresses.length === 0 || !addresses.every((address) => policy(address, port))) {
    throw new RefusedDestination(`refused ${bare}:${port} (${addresses.join(', ') || 'no address'})`);
  }
  return addresses[0] as string;
}

function splitHostPort(authority: string): { host: string; port: number } | null {
  const match = /^(\[[^\]]+\]|[^:]+):(\d{1,5})$/.exec(authority);
  if (!match?.[1] || !match[2]) {
    return null;
  }
  const port = Number(match[2]);
  return port > 0 && port < 65536 ? { host: match[1], port } : null;
}

function refuse(socket: Duplex, status: string): void {
  socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
}

export async function startEgressProxy(
  policy: DestinationPolicy,
  onRefused: (detail: string) => void = () => {},
): Promise<EgressProxy> {
  const server = http.createServer(async (request, response) => {
    let target: URL;
    try {
      target = new URL(request.url ?? '');
    } catch {
      response.writeHead(400).end();
      return;
    }
    if (target.protocol !== 'http:') {
      response.writeHead(400).end();
      return;
    }
    const port = target.port === '' ? 80 : Number(target.port);
    let address: string;
    try {
      address = await resolveAllowed(target.hostname, port, policy);
    } catch (error) {
      onRefused(String(error instanceof Error ? error.message : error));
      response.writeHead(403).end();
      return;
    }
    const headers = { ...request.headers };
    delete headers['proxy-connection'];
    delete headers['proxy-authorization'];
    const upstream = http.request(
      {
        host: address,
        port,
        method: request.method,
        path: `${target.pathname}${target.search}`,
        headers,
        setHost: false,
      },
      (upstreamResponse) => {
        response.writeHead(upstreamResponse.statusCode ?? 502, upstreamResponse.headers);
        upstreamResponse.pipe(response);
      },
    );
    upstream.on('error', () => {
      if (!response.headersSent) {
        response.writeHead(502);
      }
      response.end();
    });
    request.pipe(upstream);
  });

  // Tunnels are detached from the HTTP server once established, so closeAllConnections does
  // not see them; they are tracked here so close() ends them too.
  const tunnels = new Set<Duplex>();

  server.on('connect', async (request: http.IncomingMessage, client: Duplex, head: Buffer) => {
    tunnels.add(client);
    client.on('close', () => tunnels.delete(client));
    client.on('error', () => client.destroy());
    const destination = splitHostPort(request.url ?? '');
    if (destination === null) {
      refuse(client, '400 Bad Request');
      return;
    }
    let address: string;
    try {
      address = await resolveAllowed(destination.host, destination.port, policy);
    } catch (error) {
      onRefused(String(error instanceof Error ? error.message : error));
      refuse(client, '403 Forbidden');
      return;
    }
    let established = false;
    const upstream = net.connect({ host: address, port: destination.port }, () => {
      established = true;
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length > 0) {
        upstream.write(head);
      }
      upstream.pipe(client);
      client.pipe(upstream);
    });
    upstream.on('error', () => (established ? client.destroy() : refuse(client, '502 Bad Gateway')));
    client.on('close', () => upstream.destroy());
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as net.AddressInfo;
  return {
    port,
    close: () =>
      new Promise<void>((resolve) => {
        tunnels.forEach((tunnel) => tunnel.destroy());
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}
