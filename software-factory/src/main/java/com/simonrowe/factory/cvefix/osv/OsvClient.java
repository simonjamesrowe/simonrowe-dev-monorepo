package com.simonrowe.factory.cvefix.osv;

import com.simonrowe.factory.cvefix.config.CveRepairProperties;
import com.simonrowe.factory.cvefix.domain.MavenVersion;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asks OSV which advisories affect a Maven component version, and which release fixes each.
 *
 * <p>Dependency-Track says <em>that</em> a component is vulnerable but carries no fixed-version
 * field, and the ticket agent has no network, so the target version has to arrive in the ticket.
 * OSV's {@code /v1/query} answers it from the same advisory databases.
 *
 * <p>Redirects are not followed: the one host this talks to is configured, and nothing it returns
 * should send a request anywhere else.
 */
@Component
public class OsvClient {

  private static final int MAX_PAGES = 10;

  private final CveRepairProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public OsvClient(final CveRepairProperties properties, final ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.requestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /**
   * One advisory against the queried version.
   *
   * @param id the OSV id, e.g. {@code GHSA-xxxx-xxxx-xxxx}
   * @param fixedVersion the lowest release fixing it for the queried version, or null when OSV
   *     records no fix for it
   */
  public record Advisory(String id, String fixedVersion) {
  }

  /**
   * Every advisory OSV records against {@code coordinate} at {@code version}.
   *
   * @param coordinate {@code group:artifact}
   * @param version the version in use
   * @return the advisories; empty when OSV knows of none
   * @throws IllegalStateException when OSV cannot be read
   */
  public List<Advisory> advisories(final String coordinate, final String version) {
    List<Advisory> advisories = new ArrayList<>();
    String pageToken = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      ObjectNode query = objectMapper.createObjectNode().put("version", version);
      query.putObject("package").put("name", coordinate).put("ecosystem", "Maven");
      if (pageToken != null) {
        query.put("page_token", pageToken);
      }
      JsonNode body = post("/v1/query", query);
      for (JsonNode vulnerability : body.path("vulns")) {
        advisories.add(
            new Advisory(
                vulnerability.path("id").asText(""),
                fixedVersion(vulnerability, coordinate, MavenVersion.of(version))));
      }
      pageToken = body.path("next_page_token").asText("");
      if (pageToken.isEmpty()) {
        return List.copyOf(advisories);
      }
    }
    throw new IllegalStateException("OSV returned more than " + MAX_PAGES + " pages");
  }

  /**
   * The lowest {@code fixed} event closing a range that contains {@code current}.
   *
   * <p>Ranges are read as OSV specifies them: events in order, each {@code introduced} opening a
   * range that the next {@code fixed} or {@code last_affected} closes. A {@code last_affected}
   * range containing the version means no fix exists, which is reported as null.
   */
  static String fixedVersion(
      final JsonNode vulnerability, final String coordinate, final MavenVersion current) {
    MavenVersion best = null;
    for (JsonNode affected : vulnerability.path("affected")) {
      JsonNode pkg = affected.path("package");
      if (!"maven".equalsIgnoreCase(pkg.path("ecosystem").asText(""))
          || !coordinate.equals(pkg.path("name").asText(""))) {
        continue;
      }
      for (JsonNode range : affected.path("ranges")) {
        if (!"ECOSYSTEM".equals(range.path("type").asText(""))) {
          continue;
        }
        MavenVersion introduced = null;
        for (JsonNode event : range.path("events")) {
          if (event.has("introduced")) {
            introduced = MavenVersion.of(event.path("introduced").asText("0"));
          } else if (introduced != null && event.has("fixed")) {
            MavenVersion fixed = MavenVersion.of(event.path("fixed").asText(""));
            if (current.compareTo(introduced) >= 0 && current.compareTo(fixed) < 0
                && (best == null || fixed.compareTo(best) < 0)) {
              best = fixed;
            }
            introduced = null;
          } else if (introduced != null && event.has("last_affected")) {
            MavenVersion last = MavenVersion.of(event.path("last_affected").asText(""));
            if (current.compareTo(introduced) >= 0 && current.compareTo(last) <= 0) {
              return null;
            }
            introduced = null;
          }
        }
      }
    }
    return best == null ? null : best.toString();
  }

  private JsonNode post(final String path, final JsonNode payload) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(properties.osvBaseUrl() + path))
              .timeout(properties.requestTimeout())
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("OSV POST " + path + " returned " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("OSV POST " + path + " failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling OSV", exception);
    }
  }
}
