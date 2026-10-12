import { useCallback, useState } from 'react'

import { loadPlaylist, updatePlaylist, sameItem } from './playlistStore'
import { useEnsureAuthenticated } from './useEnsureAuthenticated'
import { useAuth } from '../auth/useAuth'
import { addFavourite, removeFavourite } from '../services/favouritesApi'
import {
  addToPlaylist,
  clearListened,
  playNextInPlaylist,
  removeFromPlaylist,
  requestPlaylistAudio,
  updatePlaylistSettings,
} from '../services/playlistApi'
import type { PlaylistAudioResult, PlaylistEntry, PlaylistItemKey } from '../types/playlist'

export interface PlaylistActions {
  /** The last thing that failed, in words for the page. */
  error: string | null
  clearError: () => void
  toggleFavourite: (entry: PlaylistEntry) => Promise<void>
  remove: (entry: PlaylistItemKey) => Promise<void>
  /** Puts an item ahead of the date order. */
  playNext: (entry: PlaylistEntry) => Promise<void>
  requestAudio: (entries: PlaylistItemKey[]) => Promise<PlaylistAudioResult[]>
  setAutoVoice: (autoVoice: boolean) => Promise<void>
  /** Queues something already heard again, from the start, at the end of the list. */
  playAgain: (entry: PlaylistItemKey) => Promise<void>
  clearHeard: () => Promise<void>
}

/**
 * The playlist's changes. Every one asks for a session first, as the heart does elsewhere on
 * the site, so a signed-out visitor who tries one gets the sign-in popup rather than a 401.
 * Changes show at once and are put back if the server refuses them.
 */
export function usePlaylistActions(): PlaylistActions {
  const ensureAuthenticated = useEnsureAuthenticated()
  const { getAccessToken } = useAuth()
  const [error, setError] = useState<string | null>(null)

  /** Runs a change after sign-in; on failure, reloads the truth and reports it. */
  const run = useCallback(async <T,>(
    change: () => Promise<T>,
    optimistic?: () => void,
  ): Promise<T | undefined> => {
    if (!(await ensureAuthenticated())) return undefined
    setError(null)
    optimistic?.()
    try {
      return await change()
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : 'The playlist could not be updated.')
      await loadPlaylist(true)
      return undefined
    }
  }, [ensureAuthenticated])

  const toggleFavourite = useCallback(async (entry: PlaylistEntry) => {
    if (entry.favourite === undefined) return
    const hearted = !entry.favourite
    await run(
      () => (hearted
        ? addFavourite(getAccessToken, 'news', entry.contentId)
        : removeFavourite(getAccessToken, 'news', entry.contentId)),
      () => updatePlaylist((data) => ({
        ...data,
        items: data.items.map((item) => (sameItem(item, entry)
          ? { ...item, favourite: hearted } : item)),
      })),
    )
  }, [getAccessToken, run])

  const remove = useCallback(async (entry: PlaylistItemKey) => {
    await run(
      () => removeFromPlaylist(getAccessToken, entry),
      () => updatePlaylist((data) => ({
        ...data,
        items: data.items.filter((item) => !sameItem(item, entry)),
      })),
    )
  }, [getAccessToken, run])

  const playNext = useCallback(async (entry: PlaylistEntry) => {
    await run(
      () => playNextInPlaylist(getAccessToken, entry),
      // The server's order puts the latest pin first, so the item goes to the front.
      () => updatePlaylist((data) => {
        const pinned = { ...entry, pinnedAt: new Date().toISOString() }
        return {
          ...data,
          items: [pinned, ...data.items.filter((item) => !sameItem(item, entry))],
        }
      }),
    )
  }, [getAccessToken, run])

  const requestAudio = useCallback(async (entries: PlaylistItemKey[]) => {
    const results = await run(() => requestPlaylistAudio(getAccessToken, entries))
    if (results) await loadPlaylist(true)
    return results ?? []
  }, [getAccessToken, run])

  const setAutoVoice = useCallback(async (autoVoice: boolean) => {
    await run(
      () => updatePlaylistSettings(getAccessToken, autoVoice),
      () => updatePlaylist((data) => ({ ...data, autoVoice })),
    )
  }, [getAccessToken, run])

  const playAgain = useCallback(async (entry: PlaylistItemKey) => {
    const done = await run(async () => {
      await addToPlaylist(getAccessToken, entry)
      return true
    })
    if (done) await loadPlaylist(true)
  }, [getAccessToken, run])

  const clearHeard = useCallback(async () => {
    await run(
      () => clearListened(getAccessToken),
      () => updatePlaylist((data) => ({
        ...data,
        items: data.items.filter((item) => item.status !== 'LISTENED'),
      })),
    )
  }, [getAccessToken, run])

  return {
    error,
    clearError: useCallback(() => setError(null), []),
    toggleFavourite,
    remove,
    playNext,
    requestAudio,
    setAutoVoice,
    playAgain,
    clearHeard,
  }
}
