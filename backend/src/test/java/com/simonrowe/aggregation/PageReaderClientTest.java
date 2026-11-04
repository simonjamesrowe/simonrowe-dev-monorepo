package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factoryadmin.FactoryAdminProperties;
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

/** A real client against a stub software-factory, so the call is checked on the wire. */
class PageReaderClientTest {

  private static final String ARTICLE = "https://openai.com/index/scaling-git-at-openai/";
  private static final String TOKEN = "trigger-token";

  private final Map<String, String> seen = new ConcurrentHashMap<>();
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String factoryAnswering(final int status, final String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/page-reads", exchange -> {
      seen.put("method", exchange.getRequestMethod());
      seen.put("token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Factory-Token")));
      seen.put("body",
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static PageReaderClient client(final boolean enabled, final String baseUrl) {
    return new PageReaderClient(
        new PageReaderProperties(enabled, Duration.ofSeconds(2)),
        new FactoryAdminProperties(baseUrl, null, TOKEN, null, null, null, null));
  }

  private static String completed(final String outcome, final Integer status, final String text) {
    return """
        {"state":"COMPLETED","result":{"outcome":"%s","httpStatus":%s,\
        "finalUrl":"%s","title":"Scaling Git at OpenAI","text":%s}}"""
        .formatted(outcome, status, ARTICLE, text == null ? "null" : "\"" + text + "\"");
  }

  @Test
  void returnsTheArticleTextAndSendsTheUrlWithTheTriggerToken() throws IOException {
    String base = factoryAnswering(200, completed("READ", 200, "  Over the past year... "));

    assertThat(client(true, base).read(ARTICLE)).contains("Over the past year...");
    assertThat(seen).containsEntry("method", "POST").containsEntry("token", TOKEN);
    assertThat(seen.get("body")).contains("\"url\":\"" + ARTICLE + "\"");
  }

  @Test
  void everyOutcomeButReadIsEmpty() throws IOException {
    for (String outcome : new String[] {"BLOCKED", "REFUSED", "EMPTY", "SOMETHING_NEW"}) {
      String base = factoryAnswering(200, completed(outcome, 403, null));
      assertThat(client(true, base).read(ARTICLE)).as(outcome).isEmpty();
      stop();
    }
  }

  @Test
  void runningAndFailedReadsAreEmpty() throws IOException {
    for (String state : new String[] {"RUNNING", "FAILED"}) {
      String base = factoryAnswering(200, "{\"state\":\"%s\",\"result\":null}".formatted(state));
      assertThat(client(true, base).read(ARTICLE)).as(state).isEmpty();
      stop();
    }
  }

  /** What the factory answers while off, or while no page-reader is polling. */
  @Test
  void unavailableFactoryIsEmptyNotAnException() throws IOException {
    assertThat(client(true, factoryAnswering(503, "{}")).read(ARTICLE)).isEmpty();
    stop();
    assertThat(client(true, "http://127.0.0.1:1").read(ARTICLE)).isEmpty();
  }

  @Test
  void switchedOffOrNotAnArticleUrlSendsNothing() throws IOException {
    String base = factoryAnswering(200, completed("READ", 200, "Body."));

    assertThat(client(false, base).read(ARTICLE)).isEmpty();
    assertThat(client(true, base).read("file:///etc/passwd")).isEmpty();
    assertThat(seen).isEmpty();
  }
}
