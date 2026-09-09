package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolGmailProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the school mailbox over the Gmail REST API.
 *
 * <p>Hand-rolled against the REST endpoints rather than pulling in the Google API client, which
 * would add a large transitive tree for three calls. The three that matter are
 * {@code messages.list}, {@code messages.get} and {@code attachments.get}.
 *
 * <p><b>Attachment bodies come back as base64url inside JSON, not as raw bytes.</b> Decoding with
 * {@code Base64.getDecoder()} instead of {@code getUrlDecoder()} fails on any payload containing
 * {@code -} or {@code _}, which is most of them, and the failure looks like a corrupt attachment
 * rather than a decoding bug.
 */
@Component
public class GmailClient {

  private static final Logger LOG = LoggerFactory.getLogger(GmailClient.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";
  private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
  private static final int PAGE_SIZE = 200;
  private static final int MAX_PAGES = 20;

  private final SchoolGmailProperties credentials;
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private String accessToken;
  private Instant accessTokenExpiry = Instant.EPOCH;

  public GmailClient(final SchoolGmailProperties credentials) {
    this.credentials = credentials;
  }

  /**
   * Whether this client can be used at all.
   *
   * @return true when a complete credential is configured
   */
  public boolean isConfigured() {
    return credentials.configured();
  }

  /**
   * Exchanges the refresh token for an access token, caching it until shortly before it expires.
   *
   * <p>A failure here is the shape a revoked credential takes — Google revokes Gmail-scoped
   * refresh tokens when the account password changes, with no other signal. Callers surface it as
   * a sync failure rather than retrying into a loop.
   *
   * @return the access token
   * @throws GmailAuthException when the credential is missing or has been revoked
   */
  public synchronized String accessToken() throws GmailAuthException {
    if (!credentials.configured()) {
      throw new GmailAuthException("No Gmail credential is configured");
    }
    if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
      return accessToken;
    }
    final String form = "client_id=" + enc(credentials.clientId())
        + "&client_secret=" + enc(credentials.clientSecret())
        + "&refresh_token=" + enc(credentials.refreshToken())
        + "&grant_type=refresh_token";
    try {
      final HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .timeout(TIMEOUT)
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build();
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new GmailAuthException(
            "Refresh grant failed with HTTP " + response.statusCode()
                + " - the token may have been revoked (a Google password change does this)");
      }
      final JsonNode body = objectMapper.readTree(response.body());
      accessToken = body.path("access_token").asString();
      // Expire the cache a minute early so a long sync never uses a token that dies mid-page.
      accessTokenExpiry = Instant.now()
          .plusSeconds(Math.max(60, body.path("expires_in").asInt(3600) - 60));
      return accessToken;
    } catch (IOException e) {
      throw new GmailAuthException(
          "Could not reach Google to refresh the token: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GmailAuthException("Interrupted while refreshing the token");
    }
  }

  /**
   * Lists message ids matching a Gmail search query.
   *
   * @param query a Gmail search expression
   * @return the matching message ids, oldest page first
   * @throws GmailAuthException when the credential will not authenticate
   */
  public List<String> listMessageIds(final String query) throws GmailAuthException {
    final List<String> ids = new ArrayList<>();
    String pageToken = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      final String url = BASE + "/messages?maxResults=" + PAGE_SIZE
          + "&q=" + enc(query)
          + (pageToken == null ? "" : "&pageToken=" + enc(pageToken));
      final Optional<JsonNode> body = get(url);
      if (body.isEmpty()) {
        break;
      }
      for (JsonNode message : body.get().path("messages")) {
        ids.add(message.path("id").asString());
      }
      final String next = body.get().path("nextPageToken").asString();
      if (next == null || next.isEmpty()) {
        break;
      }
      pageToken = next;
    }
    return List.copyOf(ids);
  }

  /**
   * Fetches one message and reduces it to the fields Term Time cares about.
   *
   * @param messageId the Gmail message id
   * @return the message, or empty when it could not be read
   * @throws GmailAuthException when the credential will not authenticate
   */
  public Optional<GmailMessage> fetchMessage(final String messageId) throws GmailAuthException {
    return get(BASE + "/messages/" + messageId + "?format=full").map(GmailMessage::from);
  }

  /**
   * Downloads one attachment.
   *
   * <p>Gmail returns attachment bodies as base64<b>url</b> text inside JSON, not as raw bytes —
   * there is no binary media download for this endpoint. {@code Base64.getDecoder()} throws on
   * the {@code -}/{@code _} alphabet, and the symptom is a corrupt file rather than an obvious
   * decoding error.
   *
   * @param messageId the message the attachment belongs to
   * @param attachmentId the attachment id from the message payload
   * @return the decoded bytes, or empty when it could not be read
   * @throws GmailAuthException when the credential will not authenticate
   */
  public Optional<byte[]> fetchAttachment(final String messageId, final String attachmentId)
      throws GmailAuthException {
    return get(BASE + "/messages/" + messageId + "/attachments/" + attachmentId)
        .map(node -> node.path("data").asString(""))
        .filter(data -> !data.isEmpty())
        .map(data -> {
          try {
            return Base64.getUrlDecoder().decode(data);
          } catch (IllegalArgumentException e) {
            LOG.debug("Attachment {} did not decode: {}", attachmentId, e.getMessage());
            return new byte[0];
          }
        })
        .filter(bytes -> bytes.length > 0);
  }

  private Optional<JsonNode> get(final String url) throws GmailAuthException {
    final String token = accessToken();
    try {
      final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("Authorization", "Bearer " + token)
          .timeout(TIMEOUT)
          .GET()
          .build();
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        throw new GmailAuthException("Gmail rejected the credential with HTTP "
            + response.statusCode());
      }
      if (response.statusCode() != 200) {
        LOG.debug("Gmail returned {} for {}", response.statusCode(), url);
        return Optional.empty();
      }
      return Optional.of(objectMapper.readTree(response.body()));
    } catch (IOException e) {
      LOG.debug("Gmail request failed: {}", e.getMessage());
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /**
   * Decodes a Gmail body payload.
   *
   * <p>base64<b>url</b>, not standard base64. Gmail's alphabet uses {@code -} and {@code _}, and
   * the standard decoder throws on both.
   *
   * @param data the encoded payload
   * @return the decoded text, empty on any decoding failure
   */
  static String decode(final String data) {
    if (data == null || data.isEmpty()) {
      return "";
    }
    try {
      return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String enc(final String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** Raised when the Gmail credential is absent, rejected or revoked. */
  public static class GmailAuthException extends Exception {
    private static final long serialVersionUID = 1L;

    public GmailAuthException(final String message) {
      super(message);
    }
  }
}
