package com.simonrowe.factory.codereview.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import io.temporal.failure.ApplicationFailure;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Mints short-lived GitHub App installation tokens without exposing them to Workflow history. */
@Component
public class GitHubCredentials {

  private static final String API_VERSION = "2026-03-10";
  private static final java.time.Duration EXPIRY_MARGIN = java.time.Duration.ofMinutes(5);

  private final CodeReviewProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Clock clock;
  private final Map<Long, CachedToken> installationTokens = new ConcurrentHashMap<>();
  private volatile PrivateKey privateKey;

  @Autowired
  public GitHubCredentials(
      final CodeReviewProperties properties, final ObjectMapper objectMapper) {
    this(
        properties,
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(properties.github().requestTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
        Clock.systemUTC());
  }

  GitHubCredentials(
      final CodeReviewProperties properties,
      final ObjectMapper objectMapper,
      final HttpClient httpClient,
      final Clock clock) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.clock = clock;
  }

  public synchronized String accessToken(final Long installationId) {
    if (installationId == null) {
      return staticToken();
    }
    if (!appConfigured()) {
      if (!staticToken().isBlank()) {
        return staticToken();
      }
      throw ApplicationFailure.newNonRetryableFailure(
          "GitHub App webhook requires GITHUB_APP_CLIENT_ID and "
              + "GITHUB_APP_PRIVATE_KEY_PATH",
          "MISSING_GITHUB_APP_CREDENTIALS");
    }

    Instant now = clock.instant();
    CachedToken cached = installationTokens.get(installationId);
    if (cached != null && cached.expiresAt().isAfter(now.plus(EXPIRY_MARGIN))) {
      return cached.value();
    }
    CachedToken minted = mintInstallationToken(installationId, now);
    installationTokens.put(installationId, minted);
    return minted.value();
  }

  /**
   * Resolves the App's installation on a repository, so a manually triggered review runs the same
   * credentialed path a webhook does.
   *
   * <p>Without this the manual trigger passed a null installation id, cloned this public repository
   * anonymously, and so could not detect a credential fault at all. That is exactly how the
   * bearer-token clone bug reached production: the documented {@code publish:false} dry run passed
   * while every real review failed.
   *
   * <p>Returns {@code null} when no App is configured, leaving local development against a public
   * repository working on the static token or anonymously.
   */
  public Long installationId(final String owner, final String repository) {
    if (!appConfigured()) {
      return null;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      properties.github().apiBaseUrl()
                          + "/repos/"
                          + owner
                          + "/"
                          + repository
                          + "/installation"))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("Authorization", "Bearer " + createAppJwt(clock.instant()))
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub App installation lookup for "
                + owner
                + "/"
                + repository
                + " returned "
                + response.statusCode());
      }
      long resolved = objectMapper.readTree(response.body()).path("id").asLong();
      if (resolved <= 0) {
        throw new IllegalStateException("GitHub App installation response carried no id");
      }
      return resolved;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub App installation lookup interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("GitHub App installation lookup failed", exception);
    }
  }

  private CachedToken mintInstallationToken(final long installationId, final Instant now) {
    try {
      // `pull_requests` must be write, not read. The advisory comment goes to the issue
      // comments endpoint, but GitHub governs comments on a pull request by the pull request
      // permission, not the issue one — a token holding issues:write and pull_requests:read
      // clones and reviews fine and then fails the publish with 403.
      ObjectNode permissions =
          objectMapper
              .createObjectNode()
              .put("contents", "read")
              .put("issues", "write")
              .put("pull_requests", "write");
      ObjectNode payload = objectMapper.createObjectNode().set("permissions", permissions);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      properties.github().apiBaseUrl()
                          + "/app/installations/"
                          + installationId
                          + "/access_tokens"))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("Authorization", "Bearer " + createAppJwt(now))
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      objectMapper.writeValueAsString(payload)))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException(
            "GitHub App token endpoint returned " + response.statusCode());
      }
      JsonNode body = objectMapper.readTree(response.body());
      String token = body.path("token").asText();
      String expiresAt = body.path("expires_at").asText();
      if (token.isBlank() || expiresAt.isBlank()) {
        throw new IllegalStateException("GitHub App token response was incomplete");
      }
      return new CachedToken(token, OffsetDateTime.parse(expiresAt).toInstant());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub App token request interrupted", exception);
    } catch (IOException exception) {
      throw new IllegalStateException("GitHub App token request failed", exception);
    }
  }

  private String createAppJwt(final Instant now) {
    try {
      ObjectNode header = objectMapper.createObjectNode().put("alg", "RS256").put("typ", "JWT");
      ObjectNode payload =
          objectMapper
              .createObjectNode()
              .put("iat", now.minusSeconds(60).getEpochSecond())
              .put("exp", now.plusSeconds(9 * 60).getEpochSecond())
              .put("iss", properties.github().appClientId());
      String unsigned =
          base64Url(objectMapper.writeValueAsBytes(header))
              + "."
              + base64Url(objectMapper.writeValueAsBytes(payload));
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey());
      signer.update(unsigned.getBytes(StandardCharsets.UTF_8));
      return unsigned + "." + base64Url(signer.sign());
    } catch (GeneralSecurityException | IOException exception) {
      throw new IllegalStateException("Unable to sign GitHub App JWT", exception);
    }
  }

  private PrivateKey privateKey() {
    PrivateKey existing = privateKey;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      if (privateKey == null) {
        privateKey = readPrivateKey();
      }
      return privateKey;
    }
  }

  private PrivateKey readPrivateKey() {
    Path path = Path.of(properties.github().appPrivateKeyPath()).toAbsolutePath().normalize();
    if (!Files.isRegularFile(path)) {
      throw ApplicationFailure.newNonRetryableFailure(
          "GitHub App private key file does not exist", "MISSING_GITHUB_APP_PRIVATE_KEY");
    }
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        PEMParser parser = new PEMParser(reader)) {
      Object pem = parser.readObject();
      JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
      if (pem instanceof PEMKeyPair pair) {
        return converter.getPrivateKey(pair.getPrivateKeyInfo());
      }
      if (pem instanceof PrivateKeyInfo keyInfo) {
        return converter.getPrivateKey(keyInfo);
      }
      throw new IllegalStateException("GitHub App private key PEM type is unsupported");
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read GitHub App private key", exception);
    }
  }

  private boolean appConfigured() {
    return properties.github().appClientId() != null
        && !properties.github().appClientId().isBlank()
        && properties.github().appPrivateKeyPath() != null
        && !properties.github().appPrivateKeyPath().isBlank();
  }

  private String staticToken() {
    String token = properties.github().token();
    return token == null ? "" : token;
  }

  private static String base64Url(final byte[] input) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
  }

  private record CachedToken(String value, Instant expiresAt) {
  }
}
