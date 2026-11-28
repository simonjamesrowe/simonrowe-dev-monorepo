package com.simonrowe.coparent.school;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Offering the letters Term Time reads to families' document libraries ("From school", spec 057
 * §4).
 *
 * @param enabled whether letters and report prompts are offered at all
 * @param classifierEnabled whether a model may judge broadcast letters. Broadcasts are offered
 *     only when it calls them keepable, so with it off only personal letters and report prompts
 *     are offered
 * @param model the low-cost model that judges them
 * @param maxClassifiedPerRun how many broadcast letters one sync may send to the model; the rest
 *     wait for the next sync
 * @param lookbackDays how far back to consider what the school sent
 * @param schoolName how a letter's sender is named to the family
 */
@ConfigurationProperties("coparent.school-documents")
public record SchoolDocumentOfferProperties(
    boolean enabled,
    boolean classifierEnabled,
    String model,
    Integer maxClassifiedPerRun,
    Integer lookbackDays,
    String schoolName
) {

  static final int DEFAULT_MAX_CLASSIFIED = 10;
  static final int DEFAULT_LOOKBACK_DAYS = 60;

  public SchoolDocumentOfferProperties {
    model = model == null || model.isBlank() ? "gpt-6-luna" : model.strip();
    maxClassifiedPerRun = maxClassifiedPerRun == null || maxClassifiedPerRun < 0
        ? DEFAULT_MAX_CLASSIFIED : maxClassifiedPerRun;
    lookbackDays = lookbackDays == null || lookbackDays < 1
        ? DEFAULT_LOOKBACK_DAYS : lookbackDays;
    schoolName = schoolName == null || schoolName.isBlank()
        ? "Kilmorie Primary School" : schoolName.strip();
  }
}
