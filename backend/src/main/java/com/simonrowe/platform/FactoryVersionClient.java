package com.simonrowe.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asks {@code software-factory} and {@code deployer} which commit they are running.
 *
 * <p>Both are on the Docker network and neither is routed by nginx, so this is an
 * unauthenticated internal call. Note the port: those containers serve on <b>8090</b>.
 *
 * <p>Two properties are load-bearing for a public page:
 * <ul>
 *   <li><b>A short timeout.</b> The status endpoint must not hang because a sibling container
 *       is restarting, so a failed or slow probe degrades to {@code reachable: false} inside
 *       one second rather than propagating.</li>
 *   <li><b>A cache.</b> The page is public and cheap to hit; without a TTL every view would
 *       put two extra HTTP calls on the two containers that run deploys.</li>
 * </ul>
 */
@Component
public class FactoryVersionClient {

  private static final Logger LOG = LoggerFactory.getLogger(FactoryVersionClient.class);

  private static final String FACTORY = "software-factory";
  private static final String DEPLOYER = "deployer";
  private static final String VERSION_PATH = "/api/version";

  private final RestClient factoryClient;
  private final RestClient deployerClient;
  private final Duration cacheTtl;
  private final AtomicReference<Cached> cache = new AtomicReference<>(null);

  /**
   * Creates the client.
   *
   * @param factoryBaseUrl base URL of the {@code software-factory} container
   * @param deployerBaseUrl base URL of the {@code deployer} container
   * @param timeout connect and read timeout applied to both clients
   * @param cacheTtl how long a fetched pair of versions is reused before being re-fetched
   */
  public FactoryVersionClient(
      // Defaults on every one of these, not just the durations: an integration test context
      // has no platform.services block, and a @Value with no default would fail every
      // @SpringBootTest in the module rather than only this feature's tests.
      @Value("${platform.services.factory-base-url:http://software-factory:8090}")
      final String factoryBaseUrl,
      @Value("${platform.services.deployer-base-url:http://deployer:8090}")
      final String deployerBaseUrl,
      @Value("${platform.services.timeout:1s}") final Duration timeout,
      @Value("${platform.services.cache-ttl:60s}") final Duration cacheTtl) {
    this.factoryClient = client(factoryBaseUrl, timeout);
    this.deployerClient = client(deployerBaseUrl, timeout);
    this.cacheTtl = cacheTtl;
  }

  private static RestClient client(final String baseUrl, final Duration timeout) {
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(new RestTemplateBuilder()
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .buildRequestFactory())
        .build();
  }

  /**
   * The two sibling services' versions, in a fixed order.
   *
   * @return exactly two entries, {@code software-factory} then {@code deployer}; unreachable
   *     services report {@link ServiceVersion#unreachable(String)} rather than being omitted,
   *     because "not reporting" is information the page should show
   */
  public List<ServiceVersion> versions() {
    final Cached current = cache.get();
    if (current != null && !current.isStale(cacheTtl)) {
      return current.versions();
    }
    final List<ServiceVersion> fetched =
        List.of(fetch(FACTORY, factoryClient), fetch(DEPLOYER, deployerClient));
    cache.set(new Cached(fetched, Instant.now()));
    return fetched;
  }

  private static ServiceVersion fetch(final String name, final RestClient client) {
    try {
      final ReportedVersion reported =
          client.get().uri(VERSION_PATH).retrieve().body(ReportedVersion.class);
      if (reported == null || reported.commit() == null) {
        return ServiceVersion.unreachable(name);
      }
      return new ServiceVersion(
          name,
          reported.commit(),
          reported.shortCommit(),
          reported.commitSubject(),
          reported.commitTime(),
          reported.startedAt(),
          true);
    } catch (final RuntimeException e) {
      LOG.debug("Could not read {} version: {}", name, e.getMessage());
      return ServiceVersion.unreachable(name);
    }
  }

  /**
   * The wire shape of software-factory's {@code GET /api/version}.
   *
   * @param commit the full commit SHA
   * @param shortCommit the seven-character SHA
   * @param commitSubject the commit subject line
   * @param commitTime when the commit was authored
   * @param startedAt when the reporting process started
   */
  private record ReportedVersion(
      String commit,
      String shortCommit,
      String commitSubject,
      Instant commitTime,
      Instant startedAt) {
  }

  /**
   * A previously fetched pair of versions, with the instant they were fetched.
   *
   * @param versions the fetched versions
   * @param fetchedAt when they were fetched
   */
  private record Cached(List<ServiceVersion> versions, Instant fetchedAt) {

    boolean isStale(final Duration ttl) {
      return ttl.isZero() || ttl.isNegative()
          || fetchedAt.plus(ttl).isBefore(Instant.now());
    }
  }
}
