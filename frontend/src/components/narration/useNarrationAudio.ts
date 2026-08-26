import { useContext } from 'react'

import { NarrationAudioContext, type NarrationAudioApi } from './narrationAudioContext'

/**
 * Reads the site-wide narration audio state.
 *
 * Throws rather than returning null when there is no provider: every consumer is inside
 * `PublicLayout` or a page it renders, all of which sit under `NarrationAudioProvider`, so a null
 * context means the provider was moved or a test forgot to wrap — both of which should fail loudly
 * rather than silently render a dead button.
 */
export function useNarrationAudio(): NarrationAudioApi {
  const context = useContext(NarrationAudioContext)
  if (!context) {
    throw new Error('useNarrationAudio must be used inside a NarrationAudioProvider')
  }
  return context
}
