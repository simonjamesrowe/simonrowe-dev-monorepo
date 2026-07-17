import { visit } from 'unist-util-visit'
import type { Root, Text } from 'mdast'

// Matches a bare internal SPA path (home-relative) that we know how to route, e.g.
// /experience, /experience?job=ID, /experience#roles, /blogs/ID, /news-events, /profile.
// The trailing char class stops at whitespace and common punctuation so a trailing
// period/comma/paren in prose is not swallowed into the URL.
const INTERNAL_URL = /\/(?:experience|blogs|news-events|profile)(?:[/?#][^\s)<>\]]*)?/g

// Matches a bare absolute http(s) URL.
const EXTERNAL_URL = /https?:\/\/[^\s)<>\]]+/g

/**
 * remark plugin (safety net): turn BARE URLs that appear as plain text into proper link
 * nodes, so answers stay clickable even when the model forgets markdown link syntax.
 * Text already inside a link/image/code node is left untouched, so this never
 * double-wraps a URL the model already linked. The custom `a` renderer + allowlist still
 * decide whether each resulting link is rendered in-site, external, or stripped.
 */
export function remarkLinkify() {
  return (tree: Root): void => {
    visit(tree, 'text', (node: Text, index, parent) => {
      if (!parent || index === undefined) {
        return
      }
      // Do not linkify inside existing links, image alt text, or code.
      const parentType = (parent as { type: string }).type
      if (parentType === 'link' || parentType === 'linkReference' || parentType === 'image') {
        return
      }

      const value = node.value
      const matches: Array<{ start: number; end: number; url: string }> = []
      for (const regex of [INTERNAL_URL, EXTERNAL_URL]) {
        regex.lastIndex = 0
        let match: RegExpExecArray | null
        while ((match = regex.exec(value)) !== null) {
          // Trim a trailing sentence punctuation the URL char class may have allowed.
          const trimmed = match[0].replace(/[.,;:]+$/, '')
          matches.push({ start: match.index, end: match.index + trimmed.length, url: trimmed })
        }
      }
      if (matches.length === 0) {
        return
      }

      // Build replacement nodes: interleave plain text and link nodes, in order.
      matches.sort((a, b) => a.start - b.start)
      const nodes: Array<Text | { type: 'link'; url: string; children: Text[] }> = []
      let cursor = 0
      for (const m of matches) {
        if (m.start < cursor) {
          continue // overlapping match (already consumed) — skip
        }
        if (m.start > cursor) {
          nodes.push({ type: 'text', value: value.slice(cursor, m.start) })
        }
        nodes.push({ type: 'link', url: m.url, children: [{ type: 'text', value: m.url }] })
        cursor = m.end
      }
      if (cursor < value.length) {
        nodes.push({ type: 'text', value: value.slice(cursor) })
      }

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      ;(parent as { children: any[] }).children.splice(index, 1, ...nodes)
      return index + nodes.length
    })
  }
}
