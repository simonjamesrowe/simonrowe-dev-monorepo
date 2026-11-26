package com.simonrowe.migration.changeunits;

import com.simonrowe.dashboard.UptimeHour;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.time.Duration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Creates the TTL index on {@code uptime_hours}, the admin dashboard's probe results.
 *
 * <p>One index does both jobs: the dashboard reads a window with {@code hourStart >= from}, and
 * the same field expires each hour 120 days after it began, the dashboard's longest window.
 * Indexes only; the prober writes the documents. Not in the backup, so a restore never drops it.
 */
@ChangeUnit(id = "create-uptime-hour-indexes", order = "068", author = "simonrowe")
public class V068CreateUptimeHourIndexes {

  static final String HOUR_START_INDEX = "idx_uptime_hour_start_ttl";
  static final Duration RETENTION = Duration.ofDays(120);

  @Execution
  public void execution(final MongoTemplate mongoTemplate) {
    createIndexes(mongoTemplate);
  }

  @RollbackExecution
  public void rollback(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(UptimeHour.COLLECTION).dropIndex(HOUR_START_INDEX);
  }

  /**
   * Idempotent, so safe to call again.
   *
   * @param mongoTemplate the template to create indexes through
   */
  public static void createIndexes(final MongoTemplate mongoTemplate) {
    mongoTemplate.indexOps(UptimeHour.COLLECTION).createIndex(new Index()
        .named(HOUR_START_INDEX)
        .on("hourStart", Sort.Direction.ASC)
        .expire(RETENTION));
  }
}
