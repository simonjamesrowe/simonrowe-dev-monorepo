import { API_BASE_URL } from '../config/api'
import {
  authFetch,
  handleNoContent,
  handleResponse,
  type GetAccessToken,
} from './adminApi'
import type {
  NewSnippet,
  Snippet,
  SnippetPage,
  SnippetQuery,
  SnippetSourceType,
  TagCount,
} from '../types/snippets'

const SNIPPETS_URL = `${API_BASE_URL}/api/admin/snippets`

function jsonBody(data: unknown, method: string): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  }
}

/** Every snippet kept from one article or post, oldest first. */
export async function fetchSnippetsFor(
  getAccessToken: GetAccessToken,
  contentId: string,
  sourceType: SnippetSourceType = 'ARTICLE_SUMMARY',
): Promise<Snippet[]> {
  const token = await getAccessToken()
  const params = new URLSearchParams({ contentId, sourceType })
  return handleResponse<Snippet[]>(await authFetch(`${SNIPPETS_URL}?${params}`, token))
}

/** Article id to snippet count, for the badges on the news cards. */
export async function fetchSnippetCounts(
  getAccessToken: GetAccessToken,
): Promise<Record<string, number>> {
  const token = await getAccessToken()
  return handleResponse<Record<string, number>>(
    await authFetch(`${SNIPPETS_URL}/counts`, token))
}

/** Every tag in use, most used first. */
export async function fetchSnippetTags(getAccessToken: GetAccessToken): Promise<TagCount[]> {
  const token = await getAccessToken()
  return handleResponse<TagCount[]>(await authFetch(`${SNIPPETS_URL}/tags`, token))
}

/** Every publisher a snippet came from. */
export async function fetchSnippetSources(getAccessToken: GetAccessToken): Promise<string[]> {
  const token = await getAccessToken()
  return handleResponse<string[]>(await authFetch(`${SNIPPETS_URL}/sources`, token))
}

/**
 * The console's search. Tags and sources are sent as repeated parameters, never joined with
 * commas, because a publisher's name can contain one.
 */
export async function searchSnippets(
  getAccessToken: GetAccessToken,
  query: SnippetQuery,
): Promise<SnippetPage> {
  const params = new URLSearchParams()
  if (query.q?.trim()) params.set('q', query.q.trim())
  query.tags?.forEach(tag => params.append('tag', tag))
  query.sources?.forEach(source => params.append('source', source))
  if (query.from) params.set('from', query.from)
  if (query.to) params.set('to', query.to)
  params.set('sort', query.sort ?? 'newest')
  params.set('page', String(query.page ?? 0))
  params.set('size', String(query.size ?? 50))
  const token = await getAccessToken()
  return handleResponse<SnippetPage>(await authFetch(`${SNIPPETS_URL}?${params}`, token))
}

export async function createSnippet(
  getAccessToken: GetAccessToken,
  snippet: NewSnippet,
): Promise<Snippet> {
  const token = await getAccessToken()
  return handleResponse<Snippet>(
    await authFetch(SNIPPETS_URL, token, jsonBody(snippet, 'POST')))
}

/** Replaces the note and tags. The quote cannot be changed. */
export async function updateSnippet(
  getAccessToken: GetAccessToken,
  id: string,
  change: { note: string; tags: string[] },
): Promise<Snippet> {
  const token = await getAccessToken()
  return handleResponse<Snippet>(
    await authFetch(`${SNIPPETS_URL}/${encodeURIComponent(id)}`, token, jsonBody(change, 'PUT')))
}

/** Answers 204, so it goes through `handleNoContent`, never `handleResponse`. */
export async function deleteSnippet(getAccessToken: GetAccessToken, id: string): Promise<void> {
  const token = await getAccessToken()
  return handleNoContent(await authFetch(`${SNIPPETS_URL}/${encodeURIComponent(id)}`, token,
    { method: 'DELETE' }))
}
