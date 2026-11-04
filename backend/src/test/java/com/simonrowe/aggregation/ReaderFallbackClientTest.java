package com.simonrowe.aggregation;

import static org.assertj.core.api.Assertions.assertThat;

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

/** A real client against a stub reader, so the request it sends is checked on the wire. */
class ReaderFallbackClientTest {

  private static final String ARTICLE = "https://openai.com/index/scaling-git-at-openai/";

  private final Map<String, String> seen = new ConcurrentHashMap<>();
  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String readerAnswering(final int status, final String body) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      seen.put("path", exchange.getRequestURI().getRawPath());
      seen.put("accept", String.valueOf(exchange.getRequestHeaders().getFirst("Accept")));
      seen.put("auth", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  private static ReaderFallbackClient client(
      final boolean enabled, final String baseUrl, final String apiKey) {
    return new ReaderFallbackClient(
        new ReaderFallbackProperties(enabled, baseUrl, apiKey, Duration.ofSeconds(2)));
  }

  private static String envelope(final int originStatus, final String content) {
    return """
        {"code":200,"status":20000,"data":{"title":"Scaling Git at OpenAI",\
        "url":"%s","httpStatus":%d,"content":"%s"}}"""
        .formatted(ARTICLE, originStatus, content);
  }

  @Test
  void returnsThePageTextAndAsksForJsonByAppendingTheArticleUrl() throws IOException {
    String base = readerAnswering(200, envelope(200, "  Over the past year, the monorepo grew. "));

    assertThat(client(true, base, null).read(ARTICLE))
        .contains("Over the past year, the monorepo grew.");
    assertThat(seen.get("path")).isEqualTo("/" + ARTICLE);
    assertThat(seen.get("accept")).isEqualTo("application/json");
    assertThat(seen.get("auth")).isEqualTo("null");
  }

  @Test
  void sendsTheApiKeyWhenOneIsConfigured() throws IOException {
    String base = readerAnswering(200, envelope(200, "Body."));

    client(true, base, "jina-key").read(ARTICLE);

    assertThat(seen.get("auth")).isEqualTo("Bearer jina-key");
  }

  /** Jina reached the page, and the publisher refused it too: that text is a challenge page. */
  @Test
  void pageThePublisherRefusedTheReaderIsEmpty() throws IOException {
    String base = readerAnswering(200, envelope(403, "Just a moment... Verify you are human."));

    assertThat(client(true, base, null).read(ARTICLE)).isEmpty();
  }

  @Test
  void readerErrorIsEmpty() throws IOException {
    String base = readerAnswering(429, "{\"code\":429}");

    assertThat(client(true, base, null).read(ARTICLE)).isEmpty();
  }

  @Test
  void blankContentAndUnparseableBodiesAreEmpty() throws IOException {
    assertThat(client(true, readerAnswering(200, envelope(200, "   ")), null).read(ARTICLE))
        .isEmpty();
    stop();
    assertThat(client(true, readerAnswering(200, "<html>not json</html>"), null).read(ARTICLE))
        .isEmpty();
  }

  @Test
  void anOverLongResponseIsIgnored() throws IOException {
    String huge = "x".repeat(ReaderFallbackClient.MAX_RESPONSE_BYTES + 10);
    String base = readerAnswering(200, envelope(200, huge));

    assertThat(client(true, base, null).read(ARTICLE)).isEmpty();
  }

  @Test
  void anUnreachableReaderIsEmptyNotAnException() {
    assertThat(client(true, "http://127.0.0.1:1/", null).read(ARTICLE)).isEmpty();
  }

  @Test
  void switchedOffItSendsNothing() throws IOException {
    String base = readerAnswering(200, envelope(200, "Body."));

    assertThat(client(false, base, null).read(ARTICLE)).isEmpty();
    assertThat(seen).isEmpty();
  }

  @Test
  void onlyAbsoluteHttpUrlsWithHostAreSent() throws IOException {
    String base = readerAnswering(200, envelope(200, "Body."));
    ReaderFallbackClient client = client(true, base, null);

    for (String notAnArticle : new String[] {
        null, "", "/index/relative", "file:///etc/passwd", "javascript:alert(1)",
        "https://", "not a url"}) {
      assertThat(client.read(notAnArticle)).as(String.valueOf(notAnArticle)).isEmpty();
    }
    assertThat(seen).isEmpty();
  }

  @Test
  void defaultsToJinaWithTrailingSlashAndNeverPrintsTheKey() {
    ReaderFallbackProperties defaults = new ReaderFallbackProperties(true, null, "secret", null);
    assertThat(defaults.baseUrl()).isEqualTo("https://r.jina.ai/");
    assertThat(defaults.timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(new ReaderFallbackProperties(true, "https://reader.example", null, null).baseUrl())
        .isEqualTo("https://reader.example/");
    assertThat(defaults.toString()).doesNotContain("secret").contains("<set>");
  }
}
