package com.simonrowe.factory.codereview.github;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Mints short-lived GitHub App installation tokens without exposing them to Workflow history. */
@Component
public class GitHubCredentials {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitHubCredentials.class);

  private static final String API_VERSION = "2026-03-10";

  /**
   * The only access level {@link #accessToken} ever asks for.
   *
   * <p>A constant rather than several literals so that adding a permission cannot quietly ask for
   * a different level than the others.
   */
  private static final String WRITE = "write";

  /**
   * Permissions no credentialed path can work without, so a missing grant must refuse to mint.
   *
   * <p>{@code pull_requests} governs a pull request's reviews and comments, {@code contents} the
   * feedback loop's guidance-branch push, {@code issues} the lifecycle comments. Silently dropping
   * one of these would not fail the mint — it would 403 later, after a clean review, which is
   * harder to diagnose than refusing up front and naming what is missing.
   */
  private static final Map<String, String> REQUIRED_PERMISSIONS =
      Map.of("contents", WRITE, "issues", WRITE, "pull_requests", WRITE);

  /**
   * Permissions that buy one capability each, and whose absence must cost only that capability.
   *
   * <p>{@code checks} publishes the {@code Code Review} check run — the only review signal a merge
   * ruleset can read. Losing it costs the merge gate, which then <em>blocks</em> for want of a
   * required check: the safe direction. Asking for it when it has not been granted, by contrast,
   * costs the entire token, and with it code review, the feedback loop, CVE fixes, deploy
   * reporting and every credentialed clone at once — silently, because reporting that failure
   * needs a token too. That asymmetry is the whole reason these are held apart from {@link
   * #REQUIRED_PERMISSIONS} and filtered against the installation's real grant before the mint.
   */
  private static final Map<String, String> OPTIONAL_PERMISSIONS = Map.of("checks", WRITE);

  /** Ranks GitHub's access levels, so a granted {@code write} satisfies a needed {@code read}. */
  private static final Map<String, Integer> LEVEL_RANK =
      Map.of("read", 1, "write", 2, "admin", 3);

  private static final java.time.Duration EXPIRY_MARGIN = java.time.Duration.ofMinutes(5);

  private final CodeReviewProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Clock clock;
  private final Map<Long, CachedToken> installationTokens = new ConcurrentHashMap<>();
  private final Map<Long, CachedToken> commentTokens = new ConcurrentHashMap<>();
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
    return token(installationId, installationTokens, true);
  }

  /**
   * A token for pull-request lifecycle comments only, minted with no {@code permissions} override.
   *
   * <p>Omitting the block yields the installation's full grant, which cannot be rejected for
   * over-reaching. {@link #accessToken} asks for more than the App may have been granted, and
   * GitHub rejects that whole request with {@code 422} — which is how a permission drift took every
   * review down on 2026-08-11, silently, because reporting the failure needed a token too. Keeping
   * the comment path on its own narrower mint means the next such drift is visible on the pull
   * request instead of only in Temporal.
   */
  public synchronized String commentToken(final Long installationId) {
    return token(installationId, commentTokens, false);
  }

  private String token(
      final Long installationId,
      final Map<Long, CachedToken> cache,
      final boolean requestWritePermissions) {
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
    CachedToken cached = cache.get(installationId);
    if (cached != null && cached.expiresAt().isAfter(now.plus(EXPIRY_MARGIN))) {
      return cached.value();
    }
    CachedToken minted = mintInstallationToken(installationId, now, requestWritePermissions);
    cache.put(installationId, minted);
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

  private CachedToken mintInstallationToken(
      final long installationId, final Instant now, final boolean requestWritePermissions) {
    try {
      // `pull_requests` must be write, not read: the code-review path publishes a single
      // top-level review via the Reviews API, and the feedback loop opens guidance PRs — both
      // are governed by the pull request permission, not the issue one. `contents` must be
      // write, not read: the feedback loop's `GuidanceWorkspaceFactory.commitAndPush` pushes
      // guidance branches, and a token minted with `contents:read` gets a 403 on every push.
      // This same token also serves the code-review path, which only reads content — that
      // widening is deliberate, see the design spec's accepted-risk note on the internet-facing
      // process holding a write-capable credential.
      //
      // Requesting a permission here does NOT get silently capped to what the installation
      // holds: GitHub's access-tokens endpoint 422s the WHOLE request when any requested
      // permission exceeds the grant. Because every credentialed path in the factory shares
      // this one method, an over-reach by one line takes down code review, the feedback loop,
      // CVE fixes, deploy reporting and every credentialed clone together — and does it
      // silently, because reporting the failure needs a token too. That happened twice: once on
      // 2026-08-11 with `contents` and again on 2026-08-28 with `checks`, both times because an
      // image requesting a new permission shipped before the App's grant was bumped.
      //
      // So the block is no longer a fixed literal. It is filtered against the installation's
      // actual grant first (`requestedPermissions`), which makes that rollout ordering
      // survivable rather than fatal: an ungranted OPTIONAL permission costs only its own
      // capability, and an ungranted REQUIRED one still refuses to mint but says which. Only
      // commentToken() sends no block at all, so it stays immune either way.
      ObjectNode payload = objectMapper.createObjectNode();
      if (requestWritePermissions) {
        payload.set("permissions", requestedPermissions(installationId, now));
      }
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
      if (response.statusCode() >= 400 && response.statusCode() < 500) {
        // A 4xx here is a configuration fault, not a blip: the commonest is a 422 because the
        // requested permissions exceed the installation's grant. Retrying it three times only
        // delays the report, and GitHub's body names the offending permission, so keep it.
        throw ApplicationFailure.newNonRetryableFailure(
            "GitHub App token endpoint returned "
                + response.statusCode()
                + ": "
                + truncate(response.body()),
            "GITHUB_TOKEN_REJECTED");
      }
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

  /**
   * The permissions block to ask for: everything needed, minus optional permissions the
   * installation has not actually granted.
   *
   * <p>Refuses to mint when a {@link #REQUIRED_PERMISSIONS} entry is missing, naming it — GitHub's
   * own 422 says only "the permissions requested are not granted to this installation", which does
   * not say which one, and reading it as the newest permission is how the 2026-08-11 incident was
   * misdiagnosed for hours.
   *
   * <p>When the grant cannot be read at all, asks for the required permissions only. Sending the
   * full set instead would restore the failure mode this method exists to remove, on nothing worse
   * than a transient API blip; dropping the optional ones costs one capability that fails closed.
   */
  private ObjectNode requestedPermissions(final long installationId, final Instant now) {
    Map<String, String> granted = grantedPermissions(installationId, now);
    ObjectNode permissions = objectMapper.createObjectNode();

    if (granted != null) {
      List<String> missing = new ArrayList<>();
      for (Map.Entry<String, String> required : REQUIRED_PERMISSIONS.entrySet()) {
        if (!satisfies(granted.get(required.getKey()), required.getValue())) {
          missing.add(required.getKey() + ":" + required.getValue());
        }
      }
      if (!missing.isEmpty()) {
        throw ApplicationFailure.newNonRetryableFailure(
            "GitHub App installation "
                + installationId
                + " is missing required permissions "
                + String.join(", ", sorted(missing))
                + " — grant them on the App and accept the installation update",
            "GITHUB_PERMISSION_MISSING");
      }
    }

    for (String name : sorted(new ArrayList<>(REQUIRED_PERMISSIONS.keySet()))) {
      permissions.put(name, REQUIRED_PERMISSIONS.get(name));
    }
    for (String name : sorted(new ArrayList<>(OPTIONAL_PERMISSIONS.keySet()))) {
      String level = OPTIONAL_PERMISSIONS.get(name);
      if (granted != null && satisfies(granted.get(name), level)) {
        permissions.put(name, level);
      } else {
        // Not an error: the capability behind it degrades on its own, and saying so once per mint
        // is what turns "code review stopped gating anything" into a one-line diagnosis.
        LOGGER.warn(
            "GitHub App installation {} does not grant {}:{}; continuing without it. "
                + "The capability it backs is unavailable until the grant is accepted.",
            installationId,
            name,
            level);
      }
    }
    return permissions;
  }

  /**
   * The installation's granted permissions, or null when they could not be read.
   *
   * <p>Deliberately not cached beyond the token it is minted for: a token lives about an hour, so
   * this costs one extra call an hour, and caching it would mean a newly accepted grant stayed
   * invisible until the next restart — the opposite of what an operator fixing a drift needs.
   */
  private Map<String, String> grantedPermissions(final long installationId, final Instant now) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      properties.github().apiBaseUrl() + "/app/installations/" + installationId))
              .timeout(properties.github().requestTimeout())
              .header("Accept", "application/vnd.github+json")
              .header("Authorization", "Bearer " + createAppJwt(now))
              .header("X-GitHub-Api-Version", API_VERSION)
              .header("User-Agent", "temporal-code-reviewer")
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        LOGGER.warn(
            "GitHub App installation lookup for {} returned {}; requesting required "
                + "permissions only",
            installationId,
            response.statusCode());
        return null;
      }
      JsonNode permissions = objectMapper.readTree(response.body()).path("permissions");
      if (!permissions.isObject()) {
        LOGGER.warn(
            "GitHub App installation {} reported no permissions block; requesting required "
                + "permissions only",
            installationId);
        return null;
      }
      Map<String, String> granted = new LinkedHashMap<>();
      for (Iterator<String> names = permissions.fieldNames(); names.hasNext(); ) {
        String name = names.next();
        granted.put(name, permissions.path(name).asText());
      }
      return granted;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub App installation lookup interrupted", exception);
    } catch (IOException exception) {
      LOGGER.warn(
          "GitHub App installation lookup for {} failed; requesting required permissions only",
          installationId,
          exception);
      return null;
    }
  }

  /** Whether a granted level covers a needed one. An unknown level covers nothing. */
  private static boolean satisfies(final String grantedLevel, final String neededLevel) {
    Integer granted = grantedLevel == null ? null : LEVEL_RANK.get(grantedLevel);
    Integer needed = LEVEL_RANK.get(neededLevel);
    return granted != null && needed != null && granted >= needed;
  }

  /** Stable ordering, so the request body and the failure message do not shuffle between runs. */
  private static List<String> sorted(final List<String> values) {
    List<String> copy = new ArrayList<>(values);
    Collections.sort(copy);
    return copy;
  }

  /** Keeps GitHub's explanation without letting a large error body into a comment or a log line. */
  private static String truncate(final String body) {
    if (body == null || body.isBlank()) {
      return "(no response body)";
    }
    String collapsed = body.replaceAll("\\s+", " ").trim();
    return collapsed.length() > 200 ? collapsed.substring(0, 200) : collapsed;
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
    try (Reader reader = Files.newBufferedReader(path);
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
