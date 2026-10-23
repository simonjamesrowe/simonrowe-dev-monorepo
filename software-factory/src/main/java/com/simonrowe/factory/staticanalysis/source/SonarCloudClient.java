package com.simonrowe.factory.staticanalysis.source;

import com.simonrowe.factory.staticanalysis.config.StaticAnalysisProperties;
import com.simonrowe.factory.staticanalysis.domain.QualityGateCondition;
import com.simonrowe.factory.staticanalysis.domain.SonarIssue;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the analysed branch's latest analysis, open new-code issues and quality gate from
 * SonarCloud's Web API.
 *
 * <p>Fails rather than degrading, except for rule names. A half-read issue list would let the
 * absence sweep close tickets for findings that were simply on the page it never fetched; a
 * missing rule name only makes a ticket title less friendly.
 */
@Component
public class SonarCloudClient {

  /** SonarCloud's maximum page size for {@code api/issues/search}. */
  static final int PAGE_SIZE = 500;

  /**
   * Pages read before the result counts as truncated. Ten pages is SonarCloud's own ceiling for
   * this endpoint (it refuses past 10,000 results), and far past anything this repository has had
   * open on new code.
   */
  static final int MAX_PAGES = 10;

  /**
   * SonarCloud writes offsets without a colon ({@code +0000}), which
   * {@link DateTimeFormatter#ISO_OFFSET_DATE_TIME} refuses.
   */
  private static final DateTimeFormatter SONAR_DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

  private final StaticAnalysisProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  /**
   * Creates the client.
   *
   * @param properties the module's configuration
   * @param objectMapper mapper used to parse SonarCloud's JSON responses
   */
  public SonarCloudClient(
      final StaticAnalysisProperties properties, final ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
  }

  /**
   * The branch's most recent analysis.
   *
   * @return the analysis, or empty when SonarCloud holds none for the branch
   */
  public Optional<Analysis> latestAnalysis() {
    JsonNode analyses =
        get("/api/project_analyses/search?project=%s&branch=%s&ps=1"
            .formatted(encoded(properties.projectKey()), encoded(properties.branch())))
            .path("analyses");
    if (analyses.isEmpty()) {
      return Optional.empty();
    }
    JsonNode latest = analyses.get(0);
    return Optional.of(
        new Analysis(latest.path("revision").asText(""), instant(latest.path("date").asText(""))));
  }

  /**
   * Every open issue on the branch's new code.
   *
   * @return the issues, and whether the read stopped short of SonarCloud's total
   */
  public IssueRead newCodeIssues() {
    List<SonarIssue> issues = new ArrayList<>();
    int total = 0;
    for (int page = 1; page <= MAX_PAGES; page++) {
      JsonNode body =
          get("""
              /api/issues/search?componentKeys=%s&branch=%s&resolved=false\
              &inNewCodePeriod=true&ps=%d&p=%d"""
              .formatted(encoded(properties.projectKey()), encoded(properties.branch()),
                  PAGE_SIZE, page));
      total = body.path("paging").path("total").asInt(body.path("total").asInt(0));
      JsonNode pageIssues = body.path("issues");
      for (JsonNode issue : pageIssues) {
        issues.add(
            new SonarIssue(
                issue.path("key").asText(""),
                issue.path("rule").asText(""),
                issue.path("severity").asText(""),
                issue.path("type").asText(""),
                pathOf(issue.path("component").asText("")),
                issue.path("line").asInt(0),
                issue.path("message").asText("")));
      }
      if (pageIssues.isEmpty() || issues.size() >= total) {
        break;
      }
    }
    return new IssueRead(List.copyOf(issues), total, issues.size() < total);
  }

  /**
   * The quality gate's failing conditions on the branch.
   *
   * @return the conditions whose status is {@code ERROR}; empty when the gate passes
   */
  public List<QualityGateCondition> failedConditions() {
    JsonNode conditions =
        get("/api/qualitygates/project_status?projectKey=%s&branch=%s"
            .formatted(encoded(properties.projectKey()), encoded(properties.branch())))
            .path("projectStatus").path("conditions");
    List<QualityGateCondition> failed = new ArrayList<>();
    for (JsonNode condition : conditions) {
      if ("ERROR".equals(condition.path("status").asText())) {
        failed.add(
            new QualityGateCondition(
                condition.path("metricKey").asText(""),
                condition.path("comparator").asText(""),
                condition.path("errorThreshold").asText(""),
                condition.path("actualValue").asText("")));
      }
    }
    return List.copyOf(failed);
  }

  /**
   * Human names for the given rules, in one request.
   *
   * <p>Best-effort, deliberately: a rule name only makes a ticket title readable, so a failed
   * lookup returns what it has rather than failing a scan that has everything it needs.
   *
   * @param ruleKeys the rule keys
   * @return rule key to name, possibly missing entries or empty
   */
  public Map<String, String> ruleNames(final Collection<String> ruleKeys) {
    if (ruleKeys.isEmpty()) {
      return Map.of();
    }
    try {
      JsonNode rules =
          get("/api/rules/search?organization=%s&rule_keys=%s&f=name&ps=%d"
              .formatted(encoded(properties.organization()),
                  encoded(String.join(",", ruleKeys)), PAGE_SIZE))
              .path("rules");
      Map<String, String> names = new HashMap<>();
      for (JsonNode rule : rules) {
        String name = rule.path("name").asText("");
        if (!name.isBlank()) {
          names.put(rule.path("key").asText(""), name);
        }
      }
      return Map.copyOf(names);
    } catch (IllegalStateException unavailable) {
      return Map.of();
    }
  }

  /**
   * Strips the {@code <projectKey>:} prefix SonarCloud puts on every component key, leaving the
   * repository-relative path.
   */
  private String pathOf(final String component) {
    String prefix = properties.projectKey() + ":";
    return component.startsWith(prefix) ? component.substring(prefix.length()) : component;
  }

  private static Instant instant(final String date) {
    if (date.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(date, SONAR_DATE).toInstant();
    } catch (DateTimeParseException unexpected) {
      return OffsetDateTime.parse(date).toInstant();
    }
  }

  private static String encoded(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private JsonNode get(final String path) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder()
            .uri(URI.create(properties.sonarBaseUrl() + path))
            .timeout(properties.requestTimeout())
            .header("Accept", "application/json")
            .GET();
    if (!properties.sonarToken().isBlank()) {
      request.header("Authorization", "Bearer " + properties.sonarToken());
    }
    String endpoint = path.substring(0, path.indexOf('?'));
    try {
      HttpResponse<String> response =
          httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "SonarCloud GET " + endpoint + " returned " + response.statusCode());
      }
      return objectMapper.readTree(response.body());
    } catch (IOException | JacksonException exception) {
      throw new IllegalStateException("SonarCloud GET " + endpoint + " failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted calling SonarCloud", exception);
    }
  }

  /**
   * One analysis of the branch.
   *
   * @param revision the commit analysed
   * @param date when the analysis ran, or null when SonarCloud sent none
   */
  public record Analysis(String revision, Instant date) {
  }

  /**
   * The result of reading every open new-code issue.
   *
   * @param issues the issues read
   * @param total how many SonarCloud says there are
   * @param truncated whether fewer were read than exist, so an absence proves nothing
   */
  public record IssueRead(List<SonarIssue> issues, int total, boolean truncated) {
  }
}
