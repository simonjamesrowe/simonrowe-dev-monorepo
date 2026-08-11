package com.simonrowe.factory.cvefix.dependencytrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.cvefix.config.CveFixProperties;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.Finding;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DependencyTrackClientTest {

  private HttpServer server;
  private final Map<String, String> responses = new ConcurrentHashMap<>();
  private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
  private final Map<String, String> seenApiKeys = new ConcurrentHashMap<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/api/v1/",
        exchange -> {
          String key = exchange.getRequestURI().getPath();
          seenApiKeys.put(key, String.valueOf(exchange.getRequestHeaders().getFirst("X-Api-Key")));
          byte[] body = responses.getOrDefault(key, "[]").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(statuses.getOrDefault(key, 200), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private DependencyTrackClient client() {
    CveFixProperties.DependencyTrack config =
        new CveFixProperties.DependencyTrack(
            "http://localhost:" + server.getAddress().getPort(),
            "test-key",
            List.of("simonrowe-dev/backend"),
            Duration.ofSeconds(5));
    return new DependencyTrackClient(config, new ObjectMapper());
  }

  @Test
  void fetchesUnsuppressedFindingsAndSendsTheApiKey() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/backend","uuid":"u1"},
         {"name":"simonrowe-dev/container","uuid":"u2"}]
        """);
    responses.put(
        "/api/v1/finding/project/u1",
        """
        [{"component":{"purl":"pkg:maven/com.foo/bar@1.0","name":"bar","version":"1.0"},
          "vulnerability":{"vulnId":"CVE-1","severity":"HIGH","recommendation":"upgrade"},
          "analysis":{"isSuppressed":false}},
         {"component":{"purl":"pkg:maven/com.foo/bar@1.0","name":"bar","version":"1.0"},
          "vulnerability":{"vulnId":"CVE-2","severity":"LOW"},
          "analysis":{"isSuppressed":true}}]
        """);

    List<Finding> findings = client().findings();

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).vulnerabilityId()).isEqualTo("CVE-1");
    assertThat(findings.get(0).purl()).isEqualTo("pkg:maven/com.foo/bar@1.0");
    assertThat(findings.get(0).recommendation()).isEqualTo("upgrade");
    assertThat(seenApiKeys.get("/api/v1/project")).isEqualTo("test-key");
  }

  @Test
  void toleratesMissingRecommendation() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/backend","uuid":"u1"}]
        """);
    responses.put(
        "/api/v1/finding/project/u1",
        """
        [{"component":{"purl":"pkg:npm/left-pad@1.0.0","name":"left-pad","version":"1.0.0"},
          "vulnerability":{"vulnId":"CVE-3","severity":"MEDIUM"},
          "analysis":{}}]
        """);

    assertThat(client().findings().get(0).recommendation()).isEmpty();
  }

  @Test
  void throwsWhenAnInScopeProjectIsMissing() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/frontend","uuid":"u9"}]
        """);

    assertThatThrownBy(() -> client().findings())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simonrowe-dev/backend");
  }

  @Test
  void throwsWhenDependencyTrackReturnsAnError() {
    responses.put(
        "/api/v1/project",
        """
        [{"name":"simonrowe-dev/backend","uuid":"u1"}]
        """);
    statuses.put("/api/v1/finding/project/u1", 503);

    assertThatThrownBy(() -> client().findings())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("503");
  }

  @Test
  void groupsFindingsByComponentWithSortedFingerprint() {
    List<Finding> findings =
        List.of(
            new Finding("pkg:maven/a/b@1", "b", "1", "CVE-9", "HIGH", ""),
            new Finding("pkg:maven/a/b@1", "b", "1", "CVE-1", "LOW", ""),
            new Finding("pkg:npm/c@2", "c", "2", "CVE-5", "HIGH", ""));

    List<ComponentFindings> grouped = ComponentFindings.group(findings);

    assertThat(grouped).hasSize(2);
    ComponentFindings first =
        grouped.stream().filter(g -> g.purl().equals("pkg:maven/a/b@1")).findFirst().orElseThrow();
    assertThat(first.vulnerabilityIds()).containsExactly("CVE-1", "CVE-9");
    assertThat(first.fingerprint()).isEqualTo("pkg:maven/a/b@1|CVE-1,CVE-9");
  }
}
