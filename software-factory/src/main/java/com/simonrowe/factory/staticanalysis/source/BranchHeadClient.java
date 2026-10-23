package com.simonrowe.factory.staticanalysis.source;

import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the analysed branch's head commit from GitHub, so the module can tell whether SonarCloud's
 * latest analysis is of it.
 *
 * <p>Anonymous on purpose. The repository is public, this is one request a run, and the factory's
 * GitHub App token is minted with write permissions this read has no need of.
 */
@Component
public class BranchHeadClient {

  private final StaticAnalysisProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates the client.
   *
   * @param properties the module's configuration
   * @param objectMapper mapper used to parse GitHub's JSON response
   */
  public BranchHeadClient(
      final StaticAnalysisProperties properties, final ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
  }

  /**
   * The branch's head commit.
   *
   * @return its sha and committer time
   * @throws IllegalStateException when GitHub cannot be read or answers without a commit
   */
  public Head head() {
    String path =
        "/repos/%s/%s/commits/%s"
            .formatted(properties.owner(), properties.repository(), properties.branch());
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(properties.githubApiBaseUrl() + path))
            .timeout(properties.requestTimeout())
            .header("Accept", "application/vnd.github+json")
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub GET " + path + " returned " + response.statusCode());
      }
      JsonNode commit = objectMapper.readTree(response.body());
      String sha = commit.path("sha").asText("");
      String committed = commit.path("commit").path("committer").path("date").asText("");
      if (sha.isBlank() || committed.isBlank()) {
        throw new IllegalStateException("GitHub GET " + path + " returned no commit");
      }
      return new Head(sha, OffsetDateTime.parse(committed).toInstant());
    } catch (IOException | JacksonException | DateTimeException exception) {
      throw new IllegalStateException("GitHub GET " + path + " failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling GitHub", exception);
    }
  }

  /**
   * A branch head.
   *
   * @param sha the commit sha
   * @param committedAt the committer timestamp, which for a squash merge is the merge time
   */
  public record Head(String sha, Instant committedAt) {
  }
}
