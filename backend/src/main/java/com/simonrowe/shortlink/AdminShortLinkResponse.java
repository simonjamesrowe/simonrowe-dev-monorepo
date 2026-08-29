package com.simonrowe.shortlink;

import java.time.Instant;

/**
 * One row of the admin shared-links table.
 *
 * @param slug the share address
 * @param shortUrl the full absolute URL, so the table can offer it for copying without
 *     assembling one
 * @param contentType which collection the content lives in
 * @param contentId the content's id
 * @param title the content's title, joined from its own collection. <b>Null when the
 *     content has been deleted</b> — an orphaned link, which this feature deliberately
 *     does not clean up, because reclaiming a slug for different content would be worse
 *     than a dead link.
 * @param clickCount human opens
 * @param lastClickedAt when a person last opened it, or null
 * @param createdAt when the link was minted
 */
public record AdminShortLinkResponse(
    String slug,
    String shortUrl,
    ShortLinkContentType contentType,
    String contentId,
    String title,
    long clickCount,
    Instant lastClickedAt,
    Instant createdAt
) {
}
