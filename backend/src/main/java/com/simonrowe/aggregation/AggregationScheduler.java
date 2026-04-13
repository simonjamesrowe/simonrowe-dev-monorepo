package com.simonrowe.aggregation;

import com.simonrowe.agents.ContentAggregationAgent;
import com.simonrowe.agents.WeeklyDigestAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class AggregationScheduler {

  private static final Logger log =
      LoggerFactory.getLogger(AggregationScheduler.class);

  private final ContentAggregationAgent aggregationAgent;
  private final WeeklyDigestAgent digestAgent;

  public AggregationScheduler(
      ContentAggregationAgent aggregationAgent,
      WeeklyDigestAgent digestAgent) {
    this.aggregationAgent = aggregationAgent;
    this.digestAgent = digestAgent;
  }

  @Scheduled(cron = "${aggregation.schedule.cron:0 0 0 * * *}")
  public void runNightlyAggregation() {
    log.info("Nightly content aggregation starting");
    aggregationAgent.runAggregation();
  }

  @Scheduled(cron = "${aggregation.digest.cron:0 0 0 */3 * *}")
  public void runDigestGeneration() {
    log.info("Digest blog post generation starting");
    digestAgent.generateDigest();
  }
}
