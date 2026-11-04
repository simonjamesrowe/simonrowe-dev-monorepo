import { BlockList, isIP } from 'node:net';

/**
 * Every range a page must never make this container connect to: loopback, private networks
 * (Docker's bridge networks included), link-local (cloud metadata), carrier-grade NAT,
 * multicast, documentation and reserved ranges. A destination is allowed only when it is
 * outside all of them, so an unfamiliar address is refused rather than trusted.
 */
const NON_PUBLIC = new BlockList();
for (const [network, prefix] of [
  ['0.0.0.0', 8],
  ['10.0.0.0', 8],
  ['100.64.0.0', 10],
  ['127.0.0.0', 8],
  ['169.254.0.0', 16],
  ['172.16.0.0', 12],
  ['192.0.0.0', 24],
  ['192.0.2.0', 24],
  ['192.88.99.0', 24],
  ['192.168.0.0', 16],
  ['198.18.0.0', 15],
  ['198.51.100.0', 24],
  ['203.0.113.0', 24],
  ['224.0.0.0', 4],
  ['240.0.0.0', 4],
] as const) {
  NON_PUBLIC.addSubnet(network, prefix, 'ipv4');
}
for (const [network, prefix] of [
  ['::', 128],
  ['::1', 128],
  ['64:ff9b:1::', 48],
  ['100::', 64],
  ['2001::', 23],
  ['2001:db8::', 32],
  ['2002::', 16],
  ['fc00::', 7],
  ['fe80::', 10],
  ['ff00::', 8],
] as const) {
  NON_PUBLIC.addSubnet(network, prefix, 'ipv6');
}

/** The IPv4 address an IPv6 address carries, for the forms that embed one. */
function embeddedIpv4(address: string): string | null {
  const lower = address.toLowerCase();
  // ::ffff:a.b.c.d (IPv4-mapped) and 64:ff9b::a.b.c.d (NAT64) in dotted form.
  const dotted = /^(?:::ffff:|64:ff9b::)(\d{1,3}(?:\.\d{1,3}){3})$/.exec(lower);
  if (dotted?.[1]) {
    return dotted[1];
  }
  // The same two forms written in hex, as URL parsers normalise them: ::ffff:7f00:1.
  const hex = /^(?:::ffff:|64:ff9b::)([0-9a-f]{1,4}):([0-9a-f]{1,4})$/.exec(lower);
  if (hex?.[1] && hex[2]) {
    const high = parseInt(hex[1], 16);
    const low = parseInt(hex[2], 16);
    return [high >> 8, high & 0xff, low >> 8, low & 0xff].join('.');
  }
  return null;
}

/** Whether a resolved IP address is a public internet address. Anything unparseable is not. */
export function isPublicAddress(address: string): boolean {
  const family = isIP(address);
  if (family === 4) {
    return !NON_PUBLIC.check(address, 'ipv4');
  }
  if (family === 6) {
    const v4 = embeddedIpv4(address);
    if (v4 !== null) {
      return isIP(v4) === 4 && !NON_PUBLIC.check(v4, 'ipv4');
    }
    return !NON_PUBLIC.check(address, 'ipv6');
  }
  return false;
}

/** The ports an article can be served on. Nothing else is reachable through the proxy. */
export const WEB_PORTS: ReadonlySet<number> = new Set([80, 443]);

/** Decides whether the proxy may connect to one resolved address and port. */
export type DestinationPolicy = (address: string, port: number) => boolean;

/** The production policy: a public address, on a web port. */
export const publicWebOnly: DestinationPolicy = (address, port) =>
  WEB_PORTS.has(port) && isPublicAddress(address);
