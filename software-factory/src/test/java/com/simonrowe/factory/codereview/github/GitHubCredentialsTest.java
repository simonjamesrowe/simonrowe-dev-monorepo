package com.simonrowe.factory.codereview.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.sun.net.httpserver.HttpServer;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubCredentialsTest {

  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void mintsAndCachesInstallationToken(@TempDir final Path directory)
      throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = tokenServer(requests, authorization, requestBody);

    try {
      CodeReviewProperties properties =
          properties(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "",
              "Iv1.test-client",
              privateKey.toString());
      GitHubCredentials credentials =
          new GitHubCredentials(
              properties,
              objectMapper,
              HttpClient.newHttpClient(),
              Clock.fixed(NOW, ZoneOffset.UTC));

      assertThat(credentials.accessToken(123L)).isEqualTo("installation-token");
      assertThat(credentials.accessToken(123L)).isEqualTo("installation-token");

      assertThat(requests).hasValue(1);
      // pull_requests must stay write: GitHub governs a pull request's reviews/comments by the
      // pull request permission, so read here fails the publish with 403 after a clean review.
      // contents must be write, not read: the feedback loop's guidance-branch push 403s
      // otherwise, regardless of what the App's own settings allow.
      assertThat(requestBody.get())
          .contains("\"contents\":\"write\"")
          .contains("\"issues\":\"write\"")
          .contains("\"pull_requests\":\"write\"");
      assertValidAppJwt(authorization.get().substring("Bearer ".length()), keyPair);
    } finally {
      server.stop(0);
    }
  }

  /**
   * The comment path must not ask for more than the installation was granted.
   *
   * <p>GitHub rejects the whole token request with {@code 422} when any requested permission
   * exceeds the grant, which is how a permission drift took every review down on 2026-08-11 —
   * silently, because reporting that failure needed a token too. Omitting the block yields the
   * installation's full grant, which cannot be rejected for over-reaching.
   */
  @Test
  void commentTokenRequestsNoPermissionsSoItCannotOverReach(@TempDir final Path directory)
      throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = tokenServer(requests, authorization, requestBody);

    try {
      GitHubCredentials credentials =
          new GitHubCredentials(
              properties(
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "",
                  "Iv1.test-client",
                  privateKey.toString()),
              objectMapper,
              HttpClient.newHttpClient(),
              Clock.fixed(NOW, ZoneOffset.UTC));

      assertThat(credentials.commentToken(123L)).isEqualTo("installation-token");

      assertThat(objectMapper.readTree(requestBody.get()).has("permissions")).isFalse();
    } finally {
      server.stop(0);
    }
  }

  /** The two tokens are cached apart, so a rejected review mint cannot poison commenting. */
  @Test
  void commentAndReviewTokensAreMintedAndCachedSeparately(@TempDir final Path directory)
      throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = tokenServer(requests, authorization, requestBody);

    try {
      GitHubCredentials credentials =
          new GitHubCredentials(
              properties(
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "",
                  "Iv1.test-client",
                  privateKey.toString()),
              objectMapper,
              HttpClient.newHttpClient(),
              Clock.fixed(NOW, ZoneOffset.UTC));

      credentials.accessToken(123L);
      credentials.commentToken(123L);
      credentials.accessToken(123L);
      credentials.commentToken(123L);

      assertThat(requests).hasValue(2);
    } finally {
      server.stop(0);
    }
  }

  /**
   * A 4xx mint is a configuration fault, not a blip.
   *
   * <p>Retrying it three times only delays the report, and GitHub's body names the offending
   * permission — the one piece of information that turns this into a two-minute fix.
   */
  @Test
  void rejectedMintIsNotRetriedAndKeepsGithubsExplanation(@TempDir final Path directory)
      throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/app/installations/123/access_tokens",
        exchange -> {
          byte[] response =
              "{\"message\":\"The permissions requested are not granted to this installation.\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(422, response.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.start();

    try {
      GitHubCredentials credentials =
          new GitHubCredentials(
              properties(
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "",
                  "Iv1.test-client",
                  privateKey.toString()),
              objectMapper,
              HttpClient.newHttpClient(),
              Clock.fixed(NOW, ZoneOffset.UTC));

      ApplicationFailure failure =
          (ApplicationFailure) catchThrowable(() -> credentials.accessToken(123L));

      assertThat(failure).isNotNull();
      assertThat(failure.isNonRetryable()).isTrue();
      assertThat(failure.getType()).isEqualTo("GITHUB_TOKEN_REJECTED");
      assertThat(failure.getOriginalMessage())
          .contains("422")
          .contains("not granted to this installation");
    } finally {
      server.stop(0);
    }
  }

  /**
   * The manual trigger resolves this so a {@code publish:false} dry run clones with a real
   * installation token instead of anonymously, which is what makes it a usable pre-deploy check.
   */
  @Test
  void resolvesTheAppInstallationForOneRepository(@TempDir final Path directory)
      throws Exception {
    KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    Path privateKey = writePrivateKey(directory, keyPair);
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpServer server = installationServer(authorization);

    try {
      GitHubCredentials credentials =
          new GitHubCredentials(
              properties(
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "",
                  "Iv1.test-client",
                  privateKey.toString()),
              objectMapper,
              HttpClient.newHttpClient(),
              Clock.fixed(NOW, ZoneOffset.UTC));

      assertThat(credentials.installationId("example", "project")).isEqualTo(987L);
      assertValidAppJwt(authorization.get().substring("Bearer ".length()), keyPair);
    } finally {
      server.stop(0);
    }
  }

  /** Local development has no App configured and must keep working against a public repository. */
  @Test
  void resolvesNoInstallationWhenNoAppIsConfigured() {
    GitHubCredentials credentials =
        new GitHubCredentials(
            properties("https://api.github.com", "development-token", "", ""), objectMapper);

    assertThat(credentials.installationId("example", "project")).isNull();
  }

  @Test
  void usesStaticTokenForManualDevelopmentRequests() {
    GitHubCredentials credentials =
        new GitHubCredentials(
            properties("https://api.github.com", "development-token", "", ""),
            objectMapper);

    assertThat(credentials.accessToken(null)).isEqualTo("development-token");
  }

  private void assertValidAppJwt(final String jwt, final KeyPair keyPair) throws Exception {
    String[] segments = jwt.split("\\.");
    assertThat(segments).hasSize(3);
    JsonNode claims =
        objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1]));
    assertThat(claims.path("iss").asText()).isEqualTo("Iv1.test-client");
    assertThat(claims.path("iat").asLong()).isEqualTo(NOW.minusSeconds(60).getEpochSecond());
    assertThat(claims.path("exp").asLong()).isEqualTo(NOW.plusSeconds(540).getEpochSecond());

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(keyPair.getPublic());
    verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.UTF_8));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2]))).isTrue();
  }

  private static Path writePrivateKey(final Path directory, final KeyPair keyPair)
      throws IOException {
    StringWriter pem = new StringWriter();
    try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
      writer.writeObject(keyPair.getPrivate());
    }
    Path path = directory.resolve("github-app.pem");
    Files.writeString(path, pem.toString());
    return path;
  }

  private static HttpServer installationServer(final AtomicReference<String> authorization)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/repos/example/project/installation",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] response =
              "{\"id\":987}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.start();
    return server;
  }

  private static HttpServer tokenServer(
      final AtomicInteger requests,
      final AtomicReference<String> authorization,
      final AtomicReference<String> requestBody)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/app/installations/123/access_tokens",
        exchange -> {
          requests.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] response =
              """
              {"token":"installation-token","expires_at":"2026-07-26T13:00:00Z"}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(201, response.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
          }
        });
    server.start();
    return server;
  }

  private static CodeReviewProperties properties(
      final String apiBaseUrl,
      final String token,
      final String appClientId,
      final String privateKeyPath) {
    return new CodeReviewProperties(
        new CodeReviewProperties.Github(
            apiBaseUrl,
            token,
            "webhook-secret",
            appClientId,
            privateKeyPath,
            Duration.ofSeconds(5)),
        new CodeReviewProperties.Agent(
            "claude",
            "sonnet",
            "medium",
            12,
            Duration.ofMinutes(15),
            Path.of("/tmp/reviewer-test"),
            2_097_152,
            80,
            "v1"),
        new CodeReviewProperties.Api(""), "https://temporal.test");
  }
}
