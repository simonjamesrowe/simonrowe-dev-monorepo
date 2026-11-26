package com.simonrowe.dashboard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Requests every public hostname once a minute and records the result in {@link UptimeHour}.
 *
 * <p><b>Why the backend probes, rather than reading the off-Pi heartbeat.</b> The heartbeat is
 * a GitHub scheduled workflow set to every 15 minutes, but GitHub runs scheduled workflows when
 * it has capacity: in November 2026 it ran three to five times a day. That is fine as a last
 * alarm and useless as an uptime figure. This prober goes out through the public internet and
 * back in through Cloudflare, the pinggy tunnel and nginx, so a pass means the whole way in
 * works, not just the container.
 *
 * <p><b>What it cannot see.</b> When the Pi or this backend is down nothing probes, so those
 * minutes are recorded as missing, never as up. The dashboard shows them as gaps.
 *
 * <p>A plain JDK {@link HttpClient}, not Spring's {@code RestClient}: Boot instruments the
 * latter, and a trace per probe every minute would be noise in every observability tool.
 */
@Component
public class UptimeProber {

  private static final Logger LOG = LoggerFactory.getLogger(UptimeProber.class);
  private static final String USER_AGENT = "simonrowe-dev-uptime-probe";

  private final DashboardProperties.Uptime config;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;
  private final HttpClient httpClient;

  /**
   * Creates the prober.
   *
   * @param properties dashboard settings
   * @param mongoTemplate where results are written
   * @param clock the time source
   */
  public UptimeProber(
      final DashboardProperties properties,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.config = properties.uptime();
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(config.timeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** Probes every target, on a fixed rate so a slow minute does not push the next one back. */
  @Scheduled(
      initialDelayString = "${dashboard.uptime.initial-delay:PT1M}",
      fixedRateString = "${dashboard.uptime.interval:PT1M}")
  public void scheduledProbe() {
    if (!config.enabled() || config.targets().isEmpty()) {
      return;
    }
    try {
      probeAll();
    } catch (RuntimeException e) {
      LOG.warn("Uptime probe round failed: {}", e.getMessage());
    }
  }

  /**
   * Probes every configured target in parallel and records each result.
   *
   * @return the results, in target order
   */
  public List<ProbeResult> probeAll() {
    List<ProbeResult> results = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<ProbeResult>> futures = new ArrayList<>();
      for (DashboardProperties.Target target : config.targets()) {
        futures.add(executor.submit(() -> probe(target)));
      }
      for (Future<ProbeResult> future : futures) {
        results.add(future.get());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return results;
    } catch (ExecutionException e) {
      throw new IllegalStateException("Uptime probe failed unexpectedly", e.getCause());
    }
    for (ProbeResult result : results) {
      record(result);
    }
    return results;
  }

  /**
   * Requests one target. Never throws: every way a request can fail is a failed probe.
   *
   * @param target what to probe
   * @return the result
   */
  ProbeResult probe(final DashboardProperties.Target target) {
    Instant started = clock.instant();
    long startNanos = System.nanoTime();
    String failure;
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(target.url()))
          .timeout(config.timeout())
          .header("User-Agent", USER_AGENT)
          .GET()
          .build();
      HttpResponse<Void> response =
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      failure = response.statusCode() == 200 ? null : "HTTP " + response.statusCode();
    } catch (HttpTimeoutException e) {
      failure = "timed out";
    } catch (IOException | IllegalArgumentException e) {
      failure = "unreachable";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      failure = "interrupted";
    }
    long millis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    return new ProbeResult(target.name(), started, millis, failure);
  }

  private void record(final ProbeResult result) {
    Instant hourStart = result.at().truncatedTo(ChronoUnit.HOURS);
    Update update = new Update()
        .setOnInsert("target", result.target())
        .setOnInsert("hourStart", hourStart)
        .inc("probes", 1)
        .inc("failures", result.failed() ? 1 : 0)
        .inc("totalMillis", result.millis());
    if (result.failed()) {
      update.set("lastFailure", result.failure()).set("lastFailureAt", result.at());
    }
    mongoTemplate.upsert(
        Query.query(Criteria.where("_id").is(UptimeHour.idFor(result.target(), hourStart))),
        update,
        UptimeHour.class);
  }

  /**
   * One probe's outcome.
   *
   * @param target the target's name
   * @param at when the probe started
   * @param millis how long it took
   * @param failure what went wrong, or null when it passed
   */
  public record ProbeResult(String target, Instant at, long millis, String failure) {

    /**
     * Whether the probe failed.
     *
     * @return true when it did not get a 200
     */
    public boolean failed() {
      return failure != null;
    }
  }
}
