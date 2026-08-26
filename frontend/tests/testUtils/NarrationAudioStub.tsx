import {
  NarrationAudioContext,
  type NarrationAudioApi,
} from '../../src/components/narration/narrationAudioContext'

/**
 * Wraps children in a stubbed narration audio context.
 *
 * Build the `value` with `narrationAudioStub()` from `./narrationAudioStub`.
 */
export function NarrationAudioStub({
  children,
  value,
}: {
  children: React.ReactNode
  value: NarrationAudioApi
}) {
  return (
    <NarrationAudioContext.Provider value={value}>
      {children}
    </NarrationAudioContext.Provider>
  )
}
