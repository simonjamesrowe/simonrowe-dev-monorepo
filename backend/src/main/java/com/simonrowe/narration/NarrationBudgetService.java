package com.simonrowe.narration;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Service;

@Service
public class NarrationBudgetService {

  private final NarrationRepository narrationRepository;
  private final NarrationProperties properties;

  public NarrationBudgetService(
      final NarrationRepository narrationRepository,
      final NarrationProperties properties
  ) {
    this.narrationRepository = narrationRepository;
    this.properties = properties;
  }

  public boolean allows(final Narration narration, final Instant now) {
    if (narration.providerRequestStarted()) {
      return true;
    }
    long limit = properties.monthlyCharacterLimit();
    if (limit <= 0) {
      return false;
    }
    return usedThisMonth(now) + narration.scriptCharacterCount() <= limit;
  }

  /**
   * Whether re-making a narration's audio to add timings fits under a share of the budget.
   *
   * <p>The re-make only ever spends below {@code share} of the month's limit, so audio a
   * reader asks for always has the rest.
   *
   * @param narration the narration to re-make
   * @param share the fraction of the monthly limit the re-make may take the total to
   * @param now the current instant
   * @return true when the re-make fits
   */
  public boolean allowsResynthesis(
      final Narration narration, final double share, final Instant now) {
    long limit = properties.monthlyCharacterLimit();
    if (limit <= 0) {
      return false;
    }
    return usedThisMonth(now) + narration.scriptCharacterCount() <= (long) (limit * share);
  }

  /**
   * Characters sent to the provider this calendar month (UTC): every narration first made
   * this month, plus every one re-made this month to add timings. One made and re-made in the
   * same month counts twice, because it was paid for twice.
   */
  private long usedThisMonth(final Instant now) {
    ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
    Instant from = utc.withDayOfMonth(1).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = utc.plusMonths(1).withDayOfMonth(1).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant();
    long made = narrationRepository
        .findByProviderRequestStartedTrueAndRequestedAtBetween(from, to)
        .stream()
        .mapToLong(Narration::scriptCharacterCount)
        .sum();
    long remade = narrationRepository.findByResynthesizedAtBetween(from, to)
        .stream()
        .mapToLong(Narration::scriptCharacterCount)
        .sum();
    return made + remade;
  }
}
