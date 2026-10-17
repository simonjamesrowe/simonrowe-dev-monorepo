package com.simonrowe.factoryadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factoryadmin.ContentWritingClient.State;
import com.simonrowe.factoryadmin.ContentWritingClient.WritingJob;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/** Tests the content-writing client against a JDK {@link HttpServer} fake. */
class ContentWritingClientTest {

  private static final String TOKEN = "trigger-token";

  private HttpServer server;
  private final Map<String, String> seen = new ConcurrentHashMap<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private ContentWritingClient clientAnswering(final int status, final String body)
      throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/writing/jobs",
        exchange -> {
          seen.put("method", exchange.getRequestMethod());
          seen.put("path", exchange.getRequestURI().getPath());
          seen.put(
              "token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Factory-Token")));
          seen.put(
              "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return client("http://127.0.0.1:" + server.getAddress().getPort());
  }

  private static ContentWritingClient client(final String baseUrl) {
    return new ContentWritingClient(
        new FactoryAdminProperties(baseUrl, null, TOKEN, null, null, null, null),
        Duration.ofSeconds(2));
  }

  @Test
  void readsJobWithTheTriggerToken() throws IOException {
    WritingJob job =
        clientAnswering(200, "{\"jobId\":\"job-1\",\"status\":\"COMPLETED\",\"text\":\"Done.\"}")
            .status("job-1");

    assertThat(job.status()).isEqualTo(State.COMPLETED);
    assertThat(job.text()).isEqualTo("Done.");
    assertThat(seen).containsEntry("method", "GET").containsEntry("path", "/api/writing/jobs/job-1")
        .containsEntry("token", TOKEN);
  }

  @Test
  void submitsTheJobIdAndPrompt() throws IOException {
    WritingJob job =
        clientAnswering(202, "{\"jobId\":\"job-1\",\"status\":\"RUNNING\"}")
            .submit("job-1", "Write a \"note\".");

    assertThat(job.status()).isEqualTo(State.RUNNING);
    assertThat(seen).containsEntry("method", "POST").containsEntry("token", TOKEN);
    assertThat(seen.get("body")).contains("\"jobId\":\"job-1\"")
        .contains("\"prompt\":\"Write a \\\"note\\\".\"");
  }

  @Test
  void answerWithNoStateIsAnError() throws IOException {
    ContentWritingClient client = clientAnswering(200, "{\"jobId\":\"job-1\"}");

    assertThatThrownBy(() -> client.status("job-1")).isInstanceOf(RestClientException.class);
  }

  @Test
  void refusalIsAnError() throws IOException {
    ContentWritingClient client = clientAnswering(503, "");

    assertThatThrownBy(() -> client.status("job-1")).isInstanceOf(RestClientException.class);
  }

  @Test
  void unreachableFactoryIsAnError() {
    assertThatThrownBy(() -> client("http://127.0.0.1:1").status("job-1"))
        .isInstanceOf(RestClientException.class);
  }
}
