package com.simonrowe.shortlink;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A short, human-readable share address for one piece of content.
 *
 * <p><b>The slug is the {@code _id}.</b> Two consequences, both load-bearing:
 *
 * <ol>
 *   <li>Resolving a share link — the hottest read on {@link ShortLinkController} — is a
 *       primary-key lookup.</li>
 *   <li>Slug uniqueness is enforced by Mongo rather than by application code that hopes.
 *       {@link ShortLinkService#ensureFor} inserts and catches the duplicate-key error;
 *       there is no read-then-write race to lose.</li>
 * </ol>
 *
 * <p>No {@code @CompoundIndex} annotation appears here on purpose. This repository runs
 * with {@code auto-index-creation} off, so an annotation would be purely decorative — and
 * the unique {@code (contentType, contentId)} index is what makes "exactly one link per
 * item" structural rather than aspirational. Indexes come from
 * {@code V029CreateShortLinksAndBackfill}.
 *
 * @param slug the address, 1–20 characters of {@code [a-z0-9-]}; also the {@code _id}
 * @param contentType which collection {@code contentId} refers to
 * @param contentId the id of the content in its own collection
 * @param clickCount human opens; fetches by link-preview services are excluded
 * @param lastClickedAt when a person last opened it, or {@code null} if never
 * @param createdAt when the link was minted
 */
@Document(collection = "short_links")
public record ShortLink(
    @Id String slug,
    ShortLinkContentType contentType,
    String contentId,
    long clickCount,
    Instant lastClickedAt,
    Instant createdAt
) {

  /**
   * A freshly minted link with no clicks yet.
   *
   * @param slug the address
   * @param contentType which collection the content lives in
   * @param contentId the content's id
   * @param createdAt the mint time
   * @return the new link
   */
  public static ShortLink minted(
      final String slug,
      final ShortLinkContentType contentType,
      final String contentId,
      final Instant createdAt
  ) {
    return new ShortLink(slug, contentType, contentId, 0L, null, createdAt);
  }
}
