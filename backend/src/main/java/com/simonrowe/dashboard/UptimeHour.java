package com.simonrowe.dashboard;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One hostname's probe results for one UTC hour.
 *
 * <p>One document per target per hour, updated with {@code $inc} as each probe lands, rather
 * than a document per probe: 90 days of nine targets is about 19,000 documents instead of
 * 1.2 million, and the dashboard only ever reads by the day. UTC hours map cleanly onto
 * {@code Europe/London} days because both of London's offsets are whole hours.
 *
 * <p>Expired after 120 days by the TTL index {@code V068CreateUptimeHourIndexes} creates.
 * Not backed up: losing it costs history, not anything a restore could bring back usefully.
 *
 * @param id {@code <target>|<hourStart>}
 * @param target the probe target's name
 * @param hourStart the start of the UTC hour
 * @param probes how many probes ran in that hour
 * @param failures how many of them did not get a {@code 200}
 * @param totalMillis the summed response time of every probe, for an average
 * @param lastFailure what the most recent failure saw, such as {@code HTTP 502}
 * @param lastFailureAt when that failure happened
 */
@Document(collection = UptimeHour.COLLECTION)
public record UptimeHour(
    @Id String id,
    String target,
    Instant hourStart,
    int probes,
    int failures,
    long totalMillis,
    String lastFailure,
    Instant lastFailureAt
) {

  public static final String COLLECTION = "uptime_hours";

  /**
   * The {@code _id} for a target's hour.
   *
   * @param target the target's name
   * @param hourStart the start of the hour
   * @return the id
   */
  public static String idFor(final String target, final Instant hourStart) {
    return target + "|" + hourStart;
  }
}
