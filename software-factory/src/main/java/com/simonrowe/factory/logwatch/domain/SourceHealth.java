package com.simonrowe.factory.logwatch.domain;

/**
 * Whether this scan's results mean anything.
 *
 * <p>The distinction {@link Status#SILENT} draws is the reason this type exists. Between roughly
 * 10 and 31 August 2026 Grafana Cloud accepted no logs at all — the free-tier monthly allowance
 * was spent, so the tenant's ingestion rate was set to {@code 0 bytes/sec} and Alloy dropped every
 * batch with a {@code 429}. Throughout, {@code alloy} was {@code Up (healthy)} (its healthcheck is
 * {@code alloy --version}, which passes while every batch is discarded) and the read credential
 * kept working, so a Loki query returned {@code {"status":"success"}} with an empty body. Every
 * layer reported success and the data was gone.
 *
 * @param status what was concluded
 * @param tier how it was concluded, which is a different quality of evidence in each case
 * @param evidence a human-readable justification, carried onto the filed ticket
 */
public record SourceHealth(Status status, Tier tier, String evidence) {

  /** What was concluded about the source. */
  public enum Status {
    /** Lines are arriving; the scan's results mean what they say. */
    ALIVE,
    /**
     * The query succeeded and returned nothing where lines were expected.
     *
     * <p>The dangerous state: nothing errors, so this reads as a clean bill of health to anything
     * that does not check for it explicitly.
     */
    SILENT,
    /** The source could not be queried at all. Loud and self-announcing; the easy case. */
    UNREACHABLE
  }

  /** How the conclusion was reached. */
  public enum Tier {
    /**
     * Alloy's own component API reported the health of its {@code loki.write} component. Direct
     * evidence: it sees the {@code 429} or {@code 401} rather than inferring from absence.
     */
    ALLOY_COMPONENT,
    /** Inferred from how many distinct containers produced lines in the window. */
    CONTAINER_COVERAGE
  }

  /** Whether the scan may treat an empty result as a clean one. */
  public boolean usable() {
    return status == Status.ALIVE;
  }
}
