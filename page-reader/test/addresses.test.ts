import { describe, expect, it } from 'vitest';
import { isPublicAddress, publicWebOnly } from '../src/addresses.js';

describe('isPublicAddress', () => {
  it.each([
    '104.18.33.45', // openai.com via Cloudflare
    '8.8.8.8',
    '2606:4700::6812:212d',
    '::ffff:8.8.8.8',
  ])('allows the public address %s', (address) => {
    expect(isPublicAddress(address)).toBe(true);
  });

  it.each([
    ['loopback', '127.0.0.1'],
    ['loopback, elsewhere in 127/8', '127.255.0.1'],
    ['Docker bridge', '172.18.0.5'],
    ['private 10/8', '10.0.0.1'],
    ['private 192.168/16', '192.168.1.20'],
    ['link-local (cloud metadata)', '169.254.169.254'],
    ['carrier-grade NAT', '100.64.0.1'],
    ['unspecified', '0.0.0.0'],
    ['multicast', '224.0.0.1'],
    ['broadcast', '255.255.255.255'],
    ['IPv6 loopback', '::1'],
    ['IPv6 unspecified', '::'],
    ['IPv6 unique local', 'fd00::1'],
    ['IPv6 link-local', 'fe80::1'],
    ['IPv4-mapped loopback, dotted', '::ffff:127.0.0.1'],
    ['IPv4-mapped loopback, hex', '::ffff:7f00:1'],
    ['IPv4-mapped Docker bridge', '::ffff:172.18.0.5'],
    ['NAT64 of a private address', '64:ff9b::10.0.0.1'],
    ['not an address', 'backend'],
    ['empty', ''],
  ])('refuses %s (%s)', (_name, address) => {
    expect(isPublicAddress(address)).toBe(false);
  });
});

describe('publicWebOnly', () => {
  it('allows a public address on 80 and 443 only', () => {
    expect(publicWebOnly('8.8.8.8', 443)).toBe(true);
    expect(publicWebOnly('8.8.8.8', 80)).toBe(true);
    expect(publicWebOnly('8.8.8.8', 7233)).toBe(false);
    expect(publicWebOnly('8.8.8.8', 22)).toBe(false);
    expect(publicWebOnly('127.0.0.1', 443)).toBe(false);
  });
});
