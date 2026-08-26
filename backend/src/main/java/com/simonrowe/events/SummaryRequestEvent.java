package com.simonrowe.events;

import java.time.Instant;

/**
 * Asks for an in-depth summary of one aggregated news article to be generated.
 *
 * <p>Published when an article is favourited. Generation is a 15-30 second model call, far
 * too slow to run inside the favourite request, so it is handed to a consumer.
 */
public record SummaryRequestEvent(String articleId, Instant requestedAt) {
}
