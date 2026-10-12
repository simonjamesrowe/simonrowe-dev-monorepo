import { useEffect, useSyncExternalStore } from 'react'

import { fetchPlaylist } from '../services/playlistApi'
import type { PlaylistEntry, PlaylistItemKey, PlaylistResponse } from '../types/playlist'

/**
 * The playlist, held once for the whole page view.
 *
 * Module state rather than React state because three things read it from different trees: the
 * header's count (which remounts on every navigation, like everything inside `PublicLayout`), the
 * playlist page, and the player, which sits above `<Routes>` and marks items heard. One request
 * serves them all, and a change made by any of them is seen by the others at once.
 */
export interface PlaylistStoreState {
  data: PlaylistResponse | null
  loading: boolean
  error: string | null
}

let state: PlaylistStoreState = { data: null, loading: false, error: null }
const listeners = new Set<() => void>()
let inflight: Promise<void> | null = null

function emit(next: PlaylistStoreState) {
  state = next
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

function snapshot(): PlaylistStoreState {
  return state
}

/**
 * Loads the playlist. Concurrent calls share one request; `force` fetches again even when it is
 * already loaded, which is how a change made elsewhere is picked up.
 */
export function loadPlaylist(force = false): Promise<void> {
  if (inflight) return inflight
  if (state.data && !force) return Promise.resolve()
  emit({ ...state, loading: true })
  inflight = fetchPlaylist()
    .then((data) => emit({ data, loading: false, error: null }))
    .catch((error: unknown) => emit({
      ...state,
      loading: false,
      error: error instanceof Error ? error.message : 'The playlist could not be loaded.',
    }))
    .finally(() => { inflight = null })
  return inflight
}

/** Applies a local change straight away, ahead of the server confirming it. */
export function updatePlaylist(change: (data: PlaylistResponse) => PlaylistResponse): void {
  if (state.data) emit({ ...state, data: change(state.data) })
}

/** For tests. */
export function resetPlaylistStore(): void {
  state = { data: null, loading: false, error: null }
  inflight = null
}

/** The playlist, loading it on first use. */
export function usePlaylist(): PlaylistStoreState {
  const current = useSyncExternalStore(subscribe, snapshot, snapshot)
  useEffect(() => { void loadPlaylist() }, [])
  return current
}

/** The playlist as already loaded, without asking for it. For the player. */
export function usePlaylistSnapshot(): PlaylistStoreState {
  return useSyncExternalStore(subscribe, snapshot, snapshot)
}

export function sameItem(a: PlaylistItemKey, b: PlaylistItemKey): boolean {
  return a.contentType === b.contentType && a.contentId === b.contentId
}

export function itemKey(item: PlaylistItemKey): string {
  return `${item.contentType}:${item.contentId}`
}

/** Queued with audio: what plays, in order. */
export function upNextOf(items: PlaylistEntry[]): PlaylistEntry[] {
  return items.filter((item) => item.status === 'QUEUED' && item.audio.state === 'READY')
}

/** Queued with no audio yet. */
export function awaitingAudioOf(items: PlaylistEntry[]): PlaylistEntry[] {
  return items.filter((item) => item.status === 'QUEUED' && item.audio.state !== 'READY')
}

/** Played to the end, most recent first (the server's order). */
export function listenedOf(items: PlaylistEntry[]): PlaylistEntry[] {
  return items.filter((item) => item.status === 'LISTENED')
}
