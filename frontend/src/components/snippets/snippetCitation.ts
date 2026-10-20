/**
 * How a snippet reads once it leaves the site: a quote, then where it came from.
 *
 * The quote is AI-written text about someone else's article, not the author's words, so the
 * citation says so ("AI summary of …"). It links the original article rather than the summary,
 * so whoever uses it can go to the source and quote the author instead (spec 054 §3.3).
 *
 * A blog post is the site's own writing, so it is cited as Simon's, linking the post.
 *
 * One formatter for every copy and download, in the browser, so the drawer's copy of an unsaved
 * passage and the console's export can never disagree.
 */

import type { SnippetSourceType } from '../../types/snippets'

export interface CitationSource {
  /** An article summary when omitted. */
  sourceType?: SnippetSourceType
  exact: string
  note?: string
  articleTitle: string
  sourceName: string
  author: string | null
  originalUrl: string
  articlePublishedAt: string | null
}

/** Lower case, no leading `#`, spaces become hyphens: the same rule the server applies. */
export function normaliseTag(raw: string): string {
  return raw.trim().replace(/^#+/, '').trim().replace(/\s+/g, '-').toLowerCase()
}

export function formatCitationDate(value: string | null): string | null {
  if (!value) return null
  return new Date(value).toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC',
  })
}

/** A blockquote that keeps the quote's paragraph breaks. */
function blockquote(text: string): string {
  return text.split('\n').map(line => (line.trim() ? `> ${line}` : '>')).join('\n')
}

function isPost(snippet: CitationSource): boolean {
  return snippet.sourceType === 'BLOG'
}

export function citation(snippet: CitationSource): string {
  const date = formatCitationDate(snippet.articlePublishedAt)
  let attribution: string
  if (isPost(snippet)) {
    const details = [snippet.sourceName, date].filter(Boolean).join(', ')
    attribution = `— ${snippet.author ? `${snippet.author}, ` : ''}"${snippet.articleTitle}"`
      + `${details ? ` (${details})` : ''}`
  } else {
    const details = [snippet.author, snippet.sourceName, date].filter(Boolean).join(', ')
    attribution = `— AI summary of "${snippet.articleTitle}"${details ? ` (${details})` : ''}`
  }
  let markdown = `${blockquote(snippet.exact)}\n\n${attribution}\n  ${snippet.originalUrl}`
  if (snippet.note?.trim()) markdown += `\n\nNote: ${snippet.note.trim()}`
  return markdown
}

/** A whole selection as one markdown document, ready to paste or save as a `.md` file. */
export function exportMarkdown(snippets: CitationSource[], tags: string[], now = new Date()): string {
  const heading = tags.length > 0 ? `# Snippets — ${tags.map(tag => `#${tag}`).join(' ')}` : '# Snippets'
  const exported = now.toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', year: 'numeric',
  })
  return `${heading}\n\n_Exported ${exported}.${aiCaveat(snippets)}_\n\n`
    + `${snippets.map(citation).join('\n\n---\n\n')}\n`
}

/** Says which quotes are model-written, so none is passed off as the original author's. */
function aiCaveat(snippets: CitationSource[]): string {
  const summaries = snippets.filter(snippet => !isPost(snippet)).length
  if (summaries === 0) return ''
  return summaries === snippets.length
    ? ' Quotes are from AI-generated summaries, not the original authors.'
    : ' Quotes cited as "AI summary of" are from AI-generated summaries, not the original authors.'
}

/**
 * Copies text, falling back to a hidden textarea where the async clipboard is missing or
 * refused (an insecure origin, an older browser).
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // Fall through to the legacy path.
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  try {
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area.remove()
  }
}
