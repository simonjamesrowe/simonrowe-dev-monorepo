package com.simonrowe.narration;

/**
 * How one kind of content supplies text to narrate, and how to tell whether an existing
 * narration is still audio of the current text.
 *
 * <p>Implementations are discovered by {@code NarrationService}, which builds a registry
 * keyed by {@link #contentType()}. Everything else in the narration package — the Kafka
 * queue, the lease and claim, the budget, the recovery scheduler, the storage — is
 * content-agnostic and stays that way.
 */
public interface NarrationSource {

  /** The content type this source handles. Must be unique across all implementations. */
  NarrationContentType contentType();

  /**
   * Builds the narration script and its fingerprint for one piece of content.
   *
   * @param contentId the content id
   * @return the descriptor
   * @throws org.springframework.web.server.ResponseStatusException 404 when the content is
   *     missing or not publicly visible, 422 when it has no narratable prose, 413 when it
   *     is too long to narrate
   */
  NarrationDescriptor scriptFor(String contentId);

  /**
   * Whether the narration is still audio of the current text for its content.
   *
   * <p>False when the content has changed underneath it (the freshly computed fingerprint
   * no longer matches the narration's id) or has gone away entirely — in which case the
   * narration is marked {@code STALE} and its audio deleted.
   *
   * @param narration the narration to check
   * @return true when it is still current
   */
  boolean isCurrent(Narration narration);

  /**
   * A narration script and the fingerprint that identifies it.
   *
   * @param id the fingerprint, which is also the narration document id and the directory
   *     the MP3 is stored under
   * @param script the plain prose to synthesise
   */
  record NarrationDescriptor(String id, String script) {
  }
}
