package com.simonrowe.factory.cvefix.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A component the agent could not fix, and the exact finding set it gave up on.
 *
 * <p>One row per component. A later run re-attempts as soon as the current fingerprint differs
 * from the stored one, so a new advisory against the same component is treated as new
 * information while a re-run of the same advisories is not.
 */
@Document(collection = "unfixable_findings")
public record UnfixableFindingRecord(
    @Id String id,
    String purl,
    String fingerprint,
    List<String> vulnerabilityIds,
    String reason,
    Instant recordedAt) {

  public UnfixableFindingRecord {
    vulnerabilityIds = vulnerabilityIds == null ? List.of() : List.copyOf(vulnerabilityIds);
  }

  /** Deterministic id for upserts: one record per component. */
  public static String idFor(final String purl) {
    return purl;
  }
}
