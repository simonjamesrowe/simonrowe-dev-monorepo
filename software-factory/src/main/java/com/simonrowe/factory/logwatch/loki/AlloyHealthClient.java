package com.simonrowe.factory.logwatch.loki;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.simonrowe.factory.logwatch.config.LogWatchProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks Alloy whether its own write path to Loki is healthy.
 *
 * <p>This is the direct tier of the source-health check, and the reason it is worth having: it
 * sees the actual error — {@code 429 ingestion rate limit exceeded (limit: 0 bytes/sec)} — rather
 * than inferring from an absence of lines. An exhausted quota, a rejected credential and a
 * genuinely quiet stack are indistinguishable from the query side and need completely different
 * fixes.
 *
 * <p><strong>Best-effort by design.</strong> Alloy publishes no host port and nginx routes nothing
 * to it; this works only because both containers share the compose network. Every failure here is
 * reported as "unreachable" rather than thrown, so the caller falls back to inferring from
 * container coverage. A missing optional signal must never fail a scan.
 */
@Component
public class AlloyHealthClient {

  private static final Logger LOG = LoggerFactory.getLogger(AlloyHealthClient.class);

  private final LogWatchProperties.Alloy config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates a client scoped to the configured Alloy endpoint.
   *
   * @param properties the module's configuration, of which the Alloy block is used
   * @param objectMapper mapper used to parse Alloy's JSON responses
   */
  public AlloyHealthClient(
      final LogWatchProperties properties, final ObjectMapper objectMapper) {
    this.config = properties.alloy();
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build();
  }

  /**
   * Reads the health of Alloy's {@code loki.write} components.
   *
   * @return the report; {@link WriteHealth#reachable()} is false when Alloy could not be asked
   */
  public WriteHealth writeHealth() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/v0/web/components"))
              .timeout(config.requestTimeout())
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 != 2) {
        LOG.debug("Alloy component API returned {}", response.statusCode());
        return WriteHealth.unreachable();
      }
      return parse(objectMapper.readTree(response.body()));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return WriteHealth.unreachable();
    } catch (Exception exception) {
      // Deliberately broad: this is an optional signal on the critical path of a scan, and no
      // failure to read it may fail the scan. The fallback tier is strictly less informative,
      // never absent.
      LOG.debug("Alloy component API unreachable: {}", exception.toString());
      return WriteHealth.unreachable();
    }
  }

  private WriteHealth parse(final JsonNode components) {
    for (JsonNode component : components) {
      String id = component.path("localID").asText(component.path("id").asText(""));
      if (!id.startsWith("loki.write")) {
        continue;
      }
      JsonNode health = component.path("health");
      String type = health.path("state").asText(health.path("health_type").asText("unknown"));
      if ("healthy".equalsIgnoreCase(type)) {
        continue;
      }
      String message = health.path("message").asText("");
      return WriteHealth.unhealthy(message.isBlank() ? id + " is " + type : message);
    }
    return WriteHealth.healthy();
  }

  /**
   * What Alloy said about its write path.
   *
   * @param reachable whether Alloy answered at all
   * @param error the error message when a {@code loki.write} component is unhealthy
   */
  public record WriteHealth(boolean reachable, Optional<String> error) {

    static WriteHealth unreachable() {
      return new WriteHealth(false, Optional.empty());
    }

    static WriteHealth healthy() {
      return new WriteHealth(true, Optional.empty());
    }

    static WriteHealth unhealthy(final String message) {
      return new WriteHealth(true, Optional.of(message));
    }
  }
}
