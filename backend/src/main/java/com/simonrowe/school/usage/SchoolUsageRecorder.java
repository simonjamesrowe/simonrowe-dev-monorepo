package com.simonrowe.school.usage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prices and records every paid model call Term Time makes.
 *
 * <p>Prices are a configurable map rather than constants: they change, and a stale hard-coded
 * figure produces a confident wrong number, which is worse than no number. Anything charged at a
 * model this does not know about is recorded at zero and logged once, so an unknown model shows
 * up as a gap rather than silently distorting the total.
 *
 * <p>Never throws. A failure to record a cost must not fail the request that incurred it.
 */
@Component
public class SchoolUsageRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolUsageRecorder.class);
  private static final double PER_MILLION = 1_000_000d;

  /**
   * Rough characters-per-token for English prose, used only where a provider reports no usage.
   * Four is the conventional approximation and is good to about ten percent — fine for a running
   * total, not fine for a bill, which is why those rows are flagged {@code estimated}.
   */
  private static final int CHARS_PER_TOKEN = 4;

  /** USD per million tokens: model to {input, cachedInput, output}. */
  private final Map<String, double[]> prices;
  private final SchoolUsageRepository repository;
  private final String clientSalt;

  public SchoolUsageRecorder(
      final SchoolUsageRepository repository,
      @Value("${school.usage.client-salt:term-time}") final String clientSalt) {
    this.repository = repository;
    this.clientSalt = clientSalt;
    this.prices = Map.of(
        "gpt-5.6-luna", new double[] {0.20, 0.02, 1.20},
        "gpt-5.4-nano", new double[] {0.20, 0.02, 1.25},
        "gpt-5-nano", new double[] {0.05, 0.005, 0.40},
        "gpt-5-mini", new double[] {0.25, 0.025, 2.00},
        "gpt-4o-mini", new double[] {0.15, 0.075, 0.60},
        "text-embedding-3-small", new double[] {0.02, 0.02, 0.0});
  }

  /**
   * Records a call whose token counts the provider reported.
   *
   * @param kind what the call was for
   * @param model the model used
   * @param inputTokens prompt tokens
   * @param outputTokens completion tokens
   * @param cachedTokens prompt tokens served from cache
   * @param sessionId the chat session, or null for ingest work
   * @param clientAddress the caller's address, hashed before storage, or null
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public void record(final SchoolUsage.Kind kind, final String model,
      final long inputTokens, final long outputTokens, final long cachedTokens,
      final String sessionId, final String clientAddress) {
    save(kind, model, inputTokens, outputTokens, cachedTokens, false, sessionId, clientAddress);
  }

  /**
   * Records a call whose token counts had to be estimated from text length.
   *
   * @param kind what the call was for
   * @param model the model used
   * @param inputChars characters sent
   * @param outputChars characters received
   */
  public void recordEstimated(final SchoolUsage.Kind kind, final String model,
      final long inputChars, final long outputChars) {
    save(kind, model, inputChars / CHARS_PER_TOKEN, outputChars / CHARS_PER_TOKEN, 0,
        true, null, null);
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private void save(final SchoolUsage.Kind kind, final String model,
      final long inputTokens, final long outputTokens, final long cachedTokens,
      final boolean estimated, final String sessionId, final String clientAddress) {
    try {
      repository.save(new SchoolUsage(
          null, Instant.now(), kind, model,
          inputTokens, outputTokens, cachedTokens,
          cost(model, inputTokens, outputTokens, cachedTokens),
          estimated, sessionId, hash(clientAddress)));
    } catch (RuntimeException e) {
      // Never fail the request that incurred the cost just because the bookkeeping failed.
      LOG.debug("Could not record {} usage: {}", kind, e.getMessage());
    }
  }

  /**
   * Computes the cost of a call.
   *
   * <p>Cached tokens are billed at the cached rate and subtracted from the uncached input, not
   * added on top — double-counting them would overstate every cached turn by the full input
   * price.
   *
   * @param model the model used
   * @param inputTokens total prompt tokens, cached ones included
   * @param outputTokens completion tokens
   * @param cachedTokens how many of the prompt tokens were cached
   * @return the cost in USD, or zero for an unpriced model
   */
  public double cost(final String model, final long inputTokens,
      final long outputTokens, final long cachedTokens) {
    final double[] price = prices.get(model == null ? "" : model.toLowerCase(Locale.ROOT));
    if (price == null) {
      LOG.debug("No price configured for model {}; recording zero", model);
      return 0d;
    }
    final long uncached = Math.max(0, inputTokens - cachedTokens);
    return (uncached * price[0] + cachedTokens * price[1] + outputTokens * price[2])
        / PER_MILLION;
  }

  /**
   * Hashes a client address so distinct visitors can be counted without storing addresses.
   *
   * <p>Salted, because an unsalted hash of an IPv4 address is trivially reversible — the whole
   * space is four billion entries. This is a rough distinct-visitor signal, not an identity.
   */
  private String hash(final String clientAddress) {
    if (clientAddress == null || clientAddress.isBlank()) {
      return null;
    }
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] bytes =
          digest.digest((clientSalt + ':' + clientAddress).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }
}
