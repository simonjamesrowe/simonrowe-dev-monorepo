import { Headphones, LogIn } from 'lucide-react'
import { useEffect, useMemo } from 'react'
import { Link } from 'react-router-dom'

import { track as trackEvent } from '../analytics/analytics'
import { useAuth } from '../auth/useAuth'
import { AwaitingAudio } from '../components/playlist/AwaitingAudio'
import { planJourney } from '../components/playlist/journey'
import { JourneyRibbon } from '../components/playlist/JourneyRibbon'
import { ListenedList } from '../components/playlist/ListenedList'
import { NowPlayingPanel } from '../components/playlist/NowPlayingPanel'
import { PlaylistQueue } from '../components/playlist/PlaylistQueue'
import { readinessOf } from '../components/playlist/readiness'
import { toTrack } from '../components/playlist/playlistFormat'
import { useNarrationAudio } from '../components/narration/useNarrationAudio'
import {
  awaitingAudioOf,
  itemKey,
  listenedOf,
  loadPlaylist,
  upNextOf,
  usePlaylist,
} from '../hooks/playlistStore'
import { useEnsureAuthenticated } from '../hooks/useEnsureAuthenticated'
import { usePageTitle } from '../hooks/usePageTitle'
import { usePlaylistActions } from '../hooks/usePlaylistActions'
import type { PlaylistEntry } from '../types/playlist'

/** How often the page checks on audio and summaries still being made. */
const REFRESH_WHILE_MAKING_MS = 10_000

/**
 * The site's shared listening queue: the summaries (and posts) people have asked for, read
 * aloud one after another.
 *
 * Anyone can see it and play it. Signed in, it can also be reordered, hearted, trimmed and
 * given more audio; signed out, those controls are simply not there, rather than present and
 * refusing.
 */
export function PlaylistPage() {
  usePageTitle('Playlist')
  const { data, loading, error } = usePlaylist()
  const { isAuthenticated } = useAuth()
  const ensureAuthenticated = useEnsureAuthenticated()
  const actions = usePlaylistActions()
  const {
    track, playing, position, rate, journeyEndsAt, upNext: playerUpNext,
    playQueue, togglePlay, setUpNext, setJourney, listen,
  } = useNarrationAudio()

  const items = useMemo(() => data?.items ?? [], [data])
  const queue = useMemo(() => upNextOf(items), [items])
  const awaiting = useMemo(() => awaitingAudioOf(items), [items])
  const listened = useMemo(() => listenedOf(items), [items])

  const currentKey = track?.fromPlaylist ? itemKey(track) : null
  const currentIndex = currentKey ? queue.findIndex((entry) => itemKey(entry) === currentKey) : -1

  // What actually plays from here: the current item, then anything chosen to play next, then
  // what follows it by date. Newer items above the current one (arrived since it started, or
  // skipped past) wait until the list is played from the top again, or are picked by hand.
  const playOrder = useMemo(() => {
    if (currentIndex < 0) return queue
    const pinned = queue.filter((entry, index) => index !== currentIndex && entry.pinnedAt)
    const after = queue.slice(currentIndex + 1).filter((entry) => !entry.pinnedAt)
    return [queue[currentIndex], ...pinned, ...after]
  }, [currentIndex, queue])
  const playheadIndex = currentIndex >= 0 ? 0 : -1

  const plan = planJourney(
    playOrder.map((entry) => entry.audio.durationSeconds ?? 0),
    playheadIndex,
    position,
    rate,
    journeyEndsAt === null ? null : Math.max(0, (journeyEndsAt - Date.now()) / 1000),
  )
  const journeySet = journeyEndsAt !== null && plan.stopIndex >= 0
  const stopKey = journeySet ? itemKey(playOrder[plan.stopIndex]) : null
  const beyondKeys = useMemo(
    () => new Set(journeySet ? playOrder.slice(plan.stopIndex + 1).map(itemKey) : []),
    [journeySet, plan.stopIndex, playOrder],
  )

  // Keep the player's "next" in step with the list as it changes: a pin, a removal, a refresh.
  useEffect(() => {
    if (!track?.fromPlaylist) return
    const following = currentIndex >= 0 ? playOrder.slice(1) : queue
    const wanted = following.map(itemKey).join('|')
    if (wanted !== playerUpNext.map(itemKey).join('|')) setUpNext(following.map(toTrack))
  }, [currentIndex, playOrder, playerUpNext, queue, setUpNext, track])

  // Audio and summaries being made finish on their own; look again until they have.
  const making = awaiting.some((entry) => readinessOf(entry).kind === 'busy')
  useEffect(() => {
    if (!making) return undefined
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void loadPlaylist(true)
    }, REFRESH_WHILE_MAKING_MS)
    return () => window.clearInterval(timer)
  }, [making])

  const play = (index: number) => {
    if (index === currentIndex) {
      togglePlay()
      return
    }
    playQueue(queue.map(toTrack), index)
    trackEvent('Playlist Played', { queueLength: queue.length })
  }

  const playOne = (entry: PlaylistEntry) => {
    listen({
      contentType: entry.contentType,
      contentId: entry.contentId,
      title: entry.title,
      href: entry.href,
      external: entry.external,
    })
  }

  const empty = !loading && data !== null && items.length === 0

  return (
    <div className="playlist">
      <div className="playlist__main">
        <p className="playlist__eyebrow">
          <Headphones aria-hidden="true" size={14} /> Playlist
        </p>
        <h1 className="playlist__title">Catch up by ear</h1>
        <p className="playlist__lede">
          Summaries of what's worth reading, read aloud newest first. Pick up where it left off on
          any device.
        </p>

        {!isAuthenticated && data !== null && items.length > 0 && (
          <p className="playlist__signed-out">
            Anyone can listen. Sign in to reorder it, heart things or make more audio.{' '}
            <button
              className="playlist-button playlist-button--ghost"
              onClick={() => { void ensureAuthenticated() }}
              type="button"
            >
              <LogIn aria-hidden="true" size={15} /> Sign in
            </button>
          </p>
        )}

        {actions.error && (
          <p className="playlist__error" role="alert">
            {actions.error}{' '}
            <button className="playlist-button playlist-button--ghost" onClick={actions.clearError} type="button">
              Dismiss
            </button>
          </p>
        )}

        {loading && data === null && <p className="playlist__loading" role="status">Loading the playlist…</p>}
        {error && data === null && (
          <p className="playlist__error" role="alert">
            {error}{' '}
            <button className="playlist-button playlist-button--ghost" onClick={() => { void loadPlaylist(true) }} type="button">
              Try again
            </button>
          </p>
        )}

        {empty && (
          <div className="playlist__empty">
            <Headphones aria-hidden="true" size={28} />
            <h2>Nothing to listen to yet</h2>
            <p>
              Summarise or heart an article on <Link to="/news-events">News &amp; Events</Link> and it
              lands here, ready to be read aloud.
            </p>
          </div>
        )}

        {queue.length > 0 && (
          <JourneyRibbon
            currentIndex={playheadIndex}
            entries={playOrder}
            journeyEndsAt={journeyEndsAt}
            onJourney={setJourney}
            plan={plan}
            positionSeconds={position}
            rate={rate}
          />
        )}

        {queue.length > 0 && (
          <section aria-labelledby="playlist-queue-title" className="playlist-section">
            <div className="playlist-section__head">
              <h2 className="playlist-section__title" id="playlist-queue-title">
                Up next <span className="playlist-section__count">{queue.length}</span>
              </h2>
              {currentIndex < 0 && (
                <button
                  className="playlist-button playlist-button--primary"
                  onClick={() => play(0)}
                  type="button"
                >
                  Play all
                </button>
              )}
            </div>
            <PlaylistQueue
              beyondKeys={beyondKeys}
              canChange={isAuthenticated}
              currentKey={currentKey}
              entries={queue}
              onPlay={play}
              onPlayNext={(entry) => { void actions.playNext(entry) }}
              onRemove={(entry) => { void actions.remove(entry) }}
              onToggleHeart={(entry) => { void actions.toggleFavourite(entry) }}
              playing={playing}
              stopKey={stopKey}
            />
          </section>
        )}

        {isAuthenticated && awaiting.length > 0 && (
          <AwaitingAudio
            autoVoice={data?.autoVoice ?? false}
            entries={awaiting}
            onAutoVoice={(autoVoice) => { void actions.setAutoVoice(autoVoice) }}
            onMakeAudio={actions.requestAudio}
            onRemove={(entry) => { void actions.remove(entry) }}
          />
        )}

        {listened.length > 0 && (
          <ListenedList
            canChange={isAuthenticated}
            entries={listened}
            onClear={() => { void actions.clearHeard() }}
            onPlay={playOne}
            onPlayAgain={(entry) => { void actions.playAgain(entry) }}
          />
        )}
      </div>

      {!empty && (
        <aside aria-label="Now playing" className="playlist__aside">
          <NowPlayingPanel variant="card" />
        </aside>
      )}
    </div>
  )
}
