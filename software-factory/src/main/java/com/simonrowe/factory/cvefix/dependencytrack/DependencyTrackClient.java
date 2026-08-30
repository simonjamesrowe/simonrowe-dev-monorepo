package com.simonrowe.factory.cvefix.dependencytrack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.Finding;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads unsuppressed findings for the in-scope Dependency-Track projects.
 *
 * <p>Fails rather than degrading: Dependency-Track shares its Postgres with Langfuse and can be
 * down on its own, and a pull request raised from half a finding set would silently under-report.
 */
@Component
public class DependencyTrackClient {

  private final CveFixProperties.DependencyTrack config;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates a client scoped to the given Dependency-Track configuration.
   *
   * @param config Dependency-Track endpoint, credential and the projects in scope
   * @param objectMapper mapper used to parse Dependency-Track's JSON responses
   */
  public DependencyTrackClient(
      final CveFixProperties.DependencyTrack config, final ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build();
  }

  /**
   * Fetches every unsuppressed finding across every in-scope project.
   *
   * @return the combined, unsuppressed findings for all configured projects
   * @throws IllegalStateException if an in-scope project is missing, or any request fails or
   *     returns a non-2xx status
   */
  public List<Finding> findings() {
    JsonNode projects = get("/api/v1/project?pageSize=100");
    List<Finding> all = new ArrayList<>();
    for (String name : config.projects()) {
      String uuid = uuidFor(projects, name);
      for (JsonNode finding : get("/api/v1/finding/project/" + uuid)) {
        if (finding.path("analysis").path("isSuppressed").asBoolean(false)) {
          continue;
        }
        JsonNode component = finding.path("component");
        JsonNode vulnerability = finding.path("vulnerability");
        all.add(
            new Finding(
                name,
                component.path("purl").asText(""),
                component.path("name").asText(""),
                component.path("version").asText(""),
                vulnerability.path("vulnId").asText(""),
                vulnerability.path("severity").asText("UNASSIGNED"),
                vulnerability.path("recommendation").asText("")));
      }
    }
    return List.copyOf(all);
  }

  private static String uuidFor(final JsonNode projects, final String name) {
    for (JsonNode project : projects) {
      if (name.equals(project.path("name").asText())) {
        return project.path("uuid").asText();
      }
    }
    throw new IllegalStateException(
        "Dependency-Track has no project named "
            + name
            + " — check the project name or whether CI has uploaded its SBOM");
  }

  private JsonNode get(final String path) {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + path))
            .timeout(config.requestTimeout())
            .header("X-Api-Key", config.apiKey())
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "Dependency-Track GET " + path + " returned " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new IllegalStateException("Dependency-Track GET " + path + " failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling Dependency-Track", exception);
    }
  }
}
