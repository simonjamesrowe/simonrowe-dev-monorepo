package com.simonrowe.factory.ticketagent.npm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.codereview.github.GitHubCredentials;
import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import com.simonrowe.factory.ticketagent.config.NpmRepairProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The Actions client against a stub GitHub. */
class NpmRepairActionsTest {

  private static final String REPO = "/repos/example/project";
  private static final String WORKFLOW = REPO + "/actions/workflows/npm-lockfile-repair.yml";

  private record Reply(int status, String body, Map<String, String> headers) {
  }

  private final Map<String, Reply> replies = new ConcurrentHashMap<>();
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private NpmRepairActions actions;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String query = exchange.getRequestURI().getRawQuery();
      String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
          + (query == null ? "" : "?" + query);
      requests.add(key);
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      Reply reply = replies.getOrDefault(key, new Reply(404, "{}", Map.of()));
      reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
      byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
      if (out.length > 0) {
        exchange.getResponseBody().write(out);
      }
      exchange.close();
    });
    server.start();
    GitHubCredentials credentials = mock(GitHubCredentials.class);
    when(credentials.accessToken(any())).thenReturn("token");
    CodeReviewProperties properties =
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "http://127.0.0.1:" + server.getAddress().getPort(), "", "", "", "",
                Duration.ofSeconds(5)),
            null, new CodeReviewProperties.Api("", null), null);
    actions = new NpmRepairActions(
        properties, TicketAgentTestProperties.of(true, "example", "project"),
        new NpmRepairProperties(null, null, null, null, 0), credentials, new ObjectMapper());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void reply(final String key, final int status, final String body) {
    replies.put(key, new Reply(status, body, Map.of()));
  }

  @Test
  void dispatchesOnMainWithTheRequestAndPackages() {
    reply("POST " + WORKFLOW + "/dispatches", 204, "");

    actions.dispatch("0b6c9a52-1f7e-4c1e-9a1e-3f2a7c9d8e10", "postcss@8.5.10", 7L);

    assertThat(bodies.get(0))
        .contains("\"ref\":\"main\"")
        .contains("\"request_id\":\"0b6c9a52-1f7e-4c1e-9a1e-3f2a7c9d8e10\"")
        .contains("\"packages\":\"postcss@8.5.10\"");
  }

  @Test
  void forbiddenDispatchNamesTheMissingGrant() {
    reply("POST " + WORKFLOW + "/dispatches", 403, "{\"message\":\"Resource not accessible\"}");

    assertThatThrownBy(() -> actions.dispatch("id", "postcss@8.5.10", 7L))
        .isInstanceOf(NpmRepairUnavailableException.class)
        .hasMessageContaining("actions: write");
  }

  @Test
  void missingWorkflowSaysSo() {
    reply("POST " + WORKFLOW + "/dispatches", 404, "{}");

    assertThatThrownBy(() -> actions.dispatch("id", "postcss@8.5.10", 7L))
        .isInstanceOf(NpmRepairUnavailableException.class)
        .hasMessageContaining("npm-lockfile-repair.yml");
  }

  @Test
  void findsTheRunByTheRequestInItsName() {
    reply("GET " + WORKFLOW + "/runs?event=workflow_dispatch&per_page=30", 200, """
        {"workflow_runs": [
          {"id": 8, "display_title": "npm lockfile repair other", "status": "completed"},
          {"id": 9, "display_title": "npm lockfile repair abc", "status": "queued",
           "conclusion": null, "html_url": "https://github.com/example/project/actions/runs/9"}
        ]}""");

    assertThat(actions.findRun("abc", 7L)).contains(new NpmRepairActions.Run(
        9, "queued", "", "https://github.com/example/project/actions/runs/9"));
    assertThat(actions.findRun("nobody", 7L)).isEmpty();
  }

  @Test
  void readsTheStateOfRun() {
    reply("GET " + REPO + "/actions/runs/9", 200, """
        {"id": 9, "status": "completed", "conclusion": "success", "html_url": "u"}""");

    NpmRepairActions.Run run = actions.run(9, 7L);

    assertThat(run.completed()).isTrue();
    assertThat(run.conclusion()).isEqualTo("success");
  }

  /** The download redirect is followed only to GitHub's artifact storage, never elsewhere. */
  @Test
  void refusesAnArtifactRedirectToAnyOtherHost() {
    reply("GET " + REPO + "/actions/runs/9/artifacts", 200, """
        {"artifacts": [{"id": 31, "name": "npm-lockfile-repair", "expired": false}]}""");
    replies.put("GET " + REPO + "/actions/artifacts/31/zip", new Reply(302, "",
        Map.of("Location", "https://attacker.example/artifact.zip")));

    assertThatThrownBy(() -> actions.artifactFiles(9, 7L))
        .hasMessageContaining("no usable redirect");
  }

  @Test
  void runWithNoArtifactIsReported() {
    reply("GET " + REPO + "/actions/runs/9/artifacts", 200,
        "{\"artifacts\": [{\"id\": 31, \"name\": \"npm-lockfile-repair\", \"expired\": true}]}");

    assertThatThrownBy(() -> actions.artifactFiles(9, 7L))
        .hasMessageContaining("no `npm-lockfile-repair` artifact");
  }

  private static byte[] zip(final Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, String> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  /** Names are compared, never used as paths, so a crafted entry is simply not read. */
  @Test
  void unzipReadsOnlyTheTwoNamedEntries() throws IOException {
    byte[] zip = zip(Map.of(
        "package-lock.json", "{}",
        "result.json", "{\"ok\":true}",
        "../../etc/package-lock.json", "evil",
        "nested/result.json", "evil"));

    Map<String, byte[]> files =
        NpmRepairActions.unzip(new ByteArrayInputStream(zip), 1024 * 1024);

    assertThat(files).containsOnlyKeys("package-lock.json", "result.json");
    assertThat(new String(files.get("result.json"), StandardCharsets.UTF_8))
        .isEqualTo("{\"ok\":true}");
  }

  @Test
  void unzipRefusesAnArtifactPastTheCap() throws IOException {
    byte[] zip = zip(Map.of("package-lock.json", "x".repeat(5_000)));

    assertThatThrownBy(() -> NpmRepairActions.unzip(new ByteArrayInputStream(zip), 1_000))
        .hasMessageContaining("larger than 1000 bytes");
  }
}
