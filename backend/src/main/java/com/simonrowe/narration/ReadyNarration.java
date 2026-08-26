package com.simonrowe.narration;

/**
 * One item that has playable audio right now, as a listing page needs it.
 *
 * <p>The projection behind {@code GET /api/narrations/ready}: everything a card needs to show
 * {@code ▶ 12 min} and start playing on click, and nothing else. No status, no version, no
 * failure code — a row exists here only because it is {@code READY}.
 *
 * <p><strong>{@code contentId} is the content's own id, not the narration's.</strong> For
 * {@link NarrationContentType#BLOG} that is the blog id. For
 * {@link NarrationContentType#ARTICLE_SUMMARY} it is the <em>aggregated article</em> id rather
 * than the summary id — see {@link ArticleSummaryNarrationSource}, which establishes that
 * convention because the article id is what the URL carries and what a caller knows. That is
 * what lets the news listing key straight off the ids it already holds, with no join and no
 * second request.
 *
 * @param contentId the blog id, or the aggregated article id for an article summary
 * @param audioUrl site-relative path under {@code /uploads/}; clients prefix the API base URL
 * @param durationSeconds audio length, rendered on the card as an approximate minute count
 */
public record ReadyNarration(
    String contentId,
    String audioUrl,
    Long durationSeconds
) {
}
