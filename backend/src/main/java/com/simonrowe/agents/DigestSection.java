package com.simonrowe.agents;

/**
 * One article's finished contribution to a digest post.
 *
 * <p>{@code title} and {@code url} come from MongoDB and are never
 * model-generated, so the link in the rendered post cannot be hallucinated.
 * {@code fallback} is true when the summarising call failed and {@code body}
 * holds the article's stored summary instead of generated prose; a digest in
 * which every section is a fallback is not worth publishing.
 */
public record DigestSection(
    String articleId,
    String title,
    String url,
    String body,
    boolean fallback
) {
}
