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
    ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
    Instant from = utc.withDayOfMonth(1).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant to = utc.plusMonths(1).withDayOfMonth(1).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant();
    long used = narrationRepository
        .findByProviderRequestStartedTrueAndRequestedAtBetween(from, to)
        .stream()
        .mapToLong(Narration::scriptCharacterCount)
        .sum();
    return used + narration.scriptCharacterCount() <= limit;
  }
}
