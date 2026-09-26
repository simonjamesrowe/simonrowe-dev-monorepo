package com.simonrowe.school.usage;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One paid model call made by Term Time.
 *
 * <p>Recorded per call rather than aggregated, so the admin page can slice by kind and period
 * without a second schema to keep in step. The corpus is small and traffic is low; at a few
 * thousand rows a month this is cheaper than maintaining rollups.
 *
 * @param id generated
 * @param at when the call was made
 * @param kind what the call was for
 * @param model the model name, so a price change can be attributed rather than smeared
 * @param inputTokens prompt tokens
 * @param outputTokens completion tokens
 * @param cachedTokens prompt tokens served from OpenAI's cache, billed at a tenth
 * @param costUsd the computed cost at the prices configured when the call was made
 * @param estimated true when the token counts were derived from text length rather than
 *     reported by the provider — an honest flag, because a cost built on an estimate should
 *     never be presented as a bill
 * @param sessionId the chat session, for distinct-session counting. Null for ingest work
 * @param clientHash a salted hash of the caller's address, for a rough distinct-visitor count.
 *     Null for ingest work. Deliberately not the address itself
 */
@Document(collection = "school_usage")
public record SchoolUsage(
    @Id String id,
    Instant at,
    Kind kind,
    String model,
    long inputTokens,
    long outputTokens,
    long cachedTokens,
    double costUsd,
    boolean estimated,
    String sessionId,
    String clientHash
) {

  /** What a model call was for. */
  public enum Kind {
    /** Answering a visitor's question. */
    CHAT,
    /** The per-turn topic gate. */
    GUARDRAIL,
    /** Deciding a document's proposed tier. */
    CLASSIFY,
    /** Pulling dated facts out of prose. */
    EXTRACT,
    /** Reading a photographed page into text. */
    TRANSCRIBE,
    /** Turning content into vectors for retrieval. */
    EMBEDDING
  }
}
