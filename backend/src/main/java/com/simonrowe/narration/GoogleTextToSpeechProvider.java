package com.simonrowe.narration;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class GoogleTextToSpeechProvider implements NarrationProvider {

  private static final String TTS_BASE = "https://texttospeech.googleapis.com";
  private static final String TTS_API_VERSION = "v1beta1";
  private static final String TTS_SYNC_API_VERSION = "v1";
  private static final String STORAGE_BASE = "https://storage.googleapis.com";

  /**
   * Google's documented input ceiling for the ordinary {@code text:synthesize} endpoint,
   * in UTF-8 bytes.
   */
  private static final int MAX_IMMEDIATE_BYTES = 5000;

  private final NarrationProperties properties;
  private final RestClient restClient;
  @Nullable
  private final GoogleCredentials credentials;

  public GoogleTextToSpeechProvider(
      final NarrationProperties properties,
      final RestClient googleNarrationRestClient,
      @Nullable final GoogleCredentials narrationGoogleCredentials
  ) {
    this.properties = properties;
    this.restClient = googleNarrationRestClient;
    this.credentials = narrationGoogleCredentials;
  }

  @Override
  public boolean isConfigured() {
    return properties.isProviderConfigured() && credentials != null;
  }

  @Override
  public int maxImmediateBytes() {
    return MAX_IMMEDIATE_BYTES;
  }

  @Override
  public byte[] synthesizeImmediately(final String script) {
    requireConfigured();
    String url = TTS_BASE + "/" + TTS_SYNC_API_VERSION + "/text:synthesize";
    Map<String, Object> body = Map.of(
        "input", Map.of("text", script),
        "audioConfig", Map.of("audioEncoding", "MP3"),
        "voice", Map.of(
            "languageCode", properties.languageCode(),
            "name", properties.voiceName()));
    try {
      Map<?, ?> response = restClient.post()
          .uri(URI.create(url))
          .headers(this::authHeaders)
          .body(body)
          .retrieve()
          .body(Map.class);
      Object audioContent = response == null ? null : response.get("audioContent");
      if (!(audioContent instanceof String encoded) || encoded.isBlank()) {
        throw providerException("Google returned no narration audio",
            FailureKind.SAFE_TO_RETRY, null);
      }
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException ex) {
      throw providerException("Google returned undecodable narration audio",
          FailureKind.SAFE_TO_RETRY, ex);
    } catch (ResourceAccessException | RestClientResponseException ex) {
      throw providerException("Unable to synthesise narration audio",
          FailureKind.SAFE_TO_RETRY, ex);
    }
  }

  @Override
  public StartResult start(final String script, final String outputObject) {
    requireConfigured();
    String parent = "projects/" + properties.projectNumber()
        + "/locations/" + properties.location();
    String url = TTS_BASE + "/" + TTS_API_VERSION + "/" + parent
        + ":synthesizeLongAudio";
    Map<String, Object> body = Map.of(
        "parent", parent,
        "input", Map.of("text", script),
        "audioConfig", Map.of("audioEncoding", "MP3"),
        "voice", Map.of(
            "languageCode", properties.languageCode(),
            "name", properties.voiceName()),
        "outputGcsUri", outputUri(outputObject));
    try {
      Map<?, ?> response = restClient.post()
          .uri(URI.create(url))
          .headers(this::authHeaders)
          .body(body)
          .retrieve()
          .body(Map.class);
      Object operationName = response == null ? null : response.get("name");
      if (!(operationName instanceof String name) || name.isBlank()) {
        throw new NarrationProviderException(
            "Google did not return an operation name",
            FailureKind.AMBIGUOUS,
            null);
      }
      return new StartResult(name);
    } catch (NarrationProviderException ex) {
      throw ex;
    } catch (ResourceAccessException ex) {
      throw providerException("Google request outcome is unknown",
          FailureKind.AMBIGUOUS, ex);
    } catch (RestClientResponseException ex) {
      FailureKind kind = ex.getStatusCode().is4xxClientError()
          ? FailureKind.SAFE_TO_RETRY : FailureKind.AMBIGUOUS;
      throw providerException("Google rejected narration generation", kind, ex);
    }
  }

  @Override
  public OperationResult poll(final String operationName) {
    requireConfigured();
    try {
      Map<?, ?> response = restClient.get()
          .uri(URI.create(operationUrl(operationName)))
          .headers(this::authHeaders)
          .retrieve()
          .body(Map.class);
      if (response == null || !Boolean.TRUE.equals(response.get("done"))) {
        return OperationResult.pending();
      }
      Object error = response.get("error");
      if (error instanceof Map<?, ?> errorMap) {
        return OperationResult.failure(sanitizedErrorCode(errorMap));
      }
      return OperationResult.success();
    } catch (ResourceAccessException | RestClientResponseException ex) {
      throw providerException("Unable to read Google narration operation",
          FailureKind.SAFE_TO_RETRY, ex);
    }
  }

  @Override
  public byte[] download(final String outputObject) {
    requireConfigured();
    String url = STORAGE_BASE + "/storage/v1/b/" + encode(properties.bucket())
        + "/o/" + encode(outputObject) + "?alt=media";
    try {
      byte[] body = restClient.get()
          .uri(URI.create(url))
          .headers(this::authHeaders)
          .retrieve()
          .body(byte[].class);
      if (body == null) {
        throw providerException("Google returned empty narration audio",
            FailureKind.SAFE_TO_RETRY, null);
      }
      return body;
    } catch (ResourceAccessException | RestClientResponseException ex) {
      throw providerException("Unable to download Google narration audio",
          FailureKind.SAFE_TO_RETRY, ex);
    }
  }

  /**
   * Applies the credentials and, crucially, the quota project.
   *
   * <p>{@code x-goog-user-project} is not optional here. With user Application Default
   * Credentials — the documented local-development path — Google rejects the call outright:
   * "The texttospeech.googleapis.com API requires a quota project, which is not set by
   * default", and attributes the request to gcloud's own client project rather than ours.
   * With a service account the header is harmless and simply keeps quota and billing
   * attributed to the configured project.
   */
  private void authHeaders(final org.springframework.http.HttpHeaders headers) {
    headers.setBearerAuth(accessToken());
    headers.set("x-goog-user-project", properties.projectId());
  }

  private synchronized String accessToken() {
    requireConfigured();
    try {
      credentials.refreshIfExpired();
      AccessToken token = credentials.getAccessToken();
      if (token == null) {
        credentials.refresh();
        token = credentials.getAccessToken();
      }
      if (token == null || token.getTokenValue() == null) {
        throw new IOException("Google credentials returned no access token");
      }
      return token.getTokenValue();
    } catch (IOException ex) {
      throw providerException("Google credentials are unavailable",
          FailureKind.UNAVAILABLE, ex);
    }
  }

  private void requireConfigured() {
    if (!isConfigured()) {
      throw providerException("Google narration is not configured",
          FailureKind.UNAVAILABLE, null);
    }
  }

  private String outputUri(final String outputObject) {
    return "gs://" + properties.bucket() + "/" + outputObject;
  }

  private String operationUrl(final String operationName) {
    String resourceName = operationName.contains("/")
        ? operationName
        : "projects/" + properties.projectNumber()
            + "/locations/" + properties.location()
            + "/operations/" + encode(operationName);
    return TTS_BASE + "/" + TTS_API_VERSION + "/" + resourceName;
  }

  private static String sanitizedErrorCode(final Map<?, ?> error) {
    Object code = error.get("code");
    return code == null ? "GOOGLE_OPERATION_FAILED" : "GOOGLE_" + code;
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static NarrationProviderException providerException(
      final String message,
      final FailureKind kind,
      @Nullable final Throwable cause
  ) {
    return new NarrationProviderException(message, kind, cause);
  }
}
