package com.simonrowe.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.simonrowe.narration.NarrationProvider.FailureKind;
import com.simonrowe.narration.NarrationProvider.NarrationProviderException;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GoogleTextToSpeechProviderTest {

  private MockRestServiceServer server;
  private GoogleTextToSpeechProvider provider;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(
        "token-value", Date.from(Instant.now().plusSeconds(3600))));
    provider = new GoogleTextToSpeechProvider(
        NarrationBudgetServiceTest.properties(1_000_000),
        builder.build(), credentials);
  }

  @Test
  void startsLongAudioWithPrivateGcsOutputAndReturnsOperation() {
    server.expect(requestTo(
            "https://texttospeech.googleapis.com/v1beta1/projects/123456789012/locations/global:"
                + "synthesizeLongAudio"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer token-value"))
        .andExpect(jsonPath("$.parent")
            .value("projects/123456789012/locations/global"))
        .andExpect(jsonPath("$.input.text").value("Read me"))
        .andExpect(jsonPath("$.audioConfig.audioEncoding").value("MP3"))
        .andExpect(jsonPath("$.voice.languageCode").value("en-GB"))
        .andExpect(jsonPath("$.voice.name").value("voice"))
        .andExpect(jsonPath("$.outputGcsUri")
            .value("gs://bucket/narrations/id.mp3"))
        .andRespond(withSuccess("{\"name\":\"23456\"}",
            MediaType.APPLICATION_JSON));

    assertThat(provider.start("Read me", "narrations/id.mp3").operationName())
        .isEqualTo("23456");
    server.verify();
  }

  @Test
  void pollsPendingSuccessAndSanitizedFailure() {
    String url = "https://texttospeech.googleapis.com/v1beta1/projects/123456789012/"
        + "locations/global/operations/1";
    server.expect(requestTo(url)).andRespond(withSuccess(
        "{\"done\":false}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(url)).andRespond(withSuccess(
        "{\"done\":true}", MediaType.APPLICATION_JSON));
    server.expect(requestTo(url)).andRespond(withSuccess(
        "{\"done\":true,\"error\":{\"code\":7,\"message\":\"private\"}}",
        MediaType.APPLICATION_JSON));

    assertThat(provider.poll("1").done()).isFalse();
    assertThat(provider.poll("1").succeeded()).isTrue();
    assertThat(provider.poll("1").failureCode()).isEqualTo("GOOGLE_7");
    server.verify();
  }

  @Test
  void downloadsEncodedObjectAsBytes() {
    byte[] audio = new byte[]{'I', 'D', '3', 1};
    server.expect(requestTo(
            "https://storage.googleapis.com/storage/v1/b/bucket/o/"
                + "narrations%2Fid.mp3?alt=media"))
        .andExpect(header("Authorization", "Bearer token-value"))
        .andRespond(withSuccess(audio, MediaType.valueOf("audio/mpeg")));

    assertThat(provider.download("narrations/id.mp3")).isEqualTo(audio);
    server.verify();
  }

  @Test
  void classifiesRejectedStartAsSafelyRetryableWithoutLeakingBody() {
    server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
        .andRespond(withBadRequest().body("credential=secret"));

    assertThatThrownBy(() -> provider.start("text", "output.mp3"))
        .isInstanceOfSatisfying(NarrationProviderException.class, exception -> {
          assertThat(exception.kind()).isEqualTo(FailureKind.SAFE_TO_RETRY);
          assertThat(exception.getMessage()).doesNotContain("secret");
        });
  }

  @Test
  void remainsDisabledWithoutCredentials() {
    GoogleTextToSpeechProvider disabled = new GoogleTextToSpeechProvider(
        NarrationBudgetServiceTest.properties(1_000), RestClient.create(), null);

    assertThat(disabled.isConfigured()).isFalse();
    assertThatThrownBy(() -> disabled.download("object"))
        .isInstanceOfSatisfying(NarrationProviderException.class,
            exception -> assertThat(exception.kind()).isEqualTo(FailureKind.UNAVAILABLE));
  }

  @Test
  void synthesisesShortScriptsThroughTheOrdinaryEndpointAsMp3() {
    // Long Audio rejects MP3 outright, so this is the only path that yields the format
    // the application stores. Note v1, not v1beta1.
    server.expect(requestTo("https://texttospeech.googleapis.com/v1/text:synthesize"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer token-value"))
        .andExpect(jsonPath("$.input.text").value("Read me"))
        .andExpect(jsonPath("$.audioConfig.audioEncoding").value("MP3"))
        .andExpect(jsonPath("$.voice.languageCode").value("en-GB"))
        .andExpect(jsonPath("$.voice.name").value("voice"))
        // No GCS involvement at all on this path.
        .andExpect(jsonPath("$.outputGcsUri").doesNotExist())
        .andRespond(withSuccess("{\"audioContent\":\"SUQzBA==\"}",
            MediaType.APPLICATION_JSON));

    assertThat(provider.synthesizeImmediately("Read me"))
        .isEqualTo(java.util.Base64.getDecoder().decode("SUQzBA=="));
    server.verify();
  }

  /**
   * Without this header, user Application Default Credentials — the documented
   * local-development path — get a 403 telling us the API "requires a quota project", and
   * Google attributes the call to gcloud's own client project instead of ours.
   */
  @Test
  void sendsTheQuotaProjectHeaderSoUserAdcIsAccepted() {
    server.expect(requestTo("https://texttospeech.googleapis.com/v1/text:synthesize"))
        .andExpect(header("x-goog-user-project", "project"))
        .andRespond(withSuccess("{\"audioContent\":\"SUQzBA==\"}",
            MediaType.APPLICATION_JSON));

    provider.synthesizeImmediately("Read me");
    server.verify();
  }

  @Test
  void longAudioStartAlsoSendsTheQuotaProjectHeader() {
    server.expect(requestTo(
            "https://texttospeech.googleapis.com/v1beta1/projects/123456789012/locations/"
                + "global:synthesizeLongAudio"))
        .andExpect(header("x-goog-user-project", "project"))
        .andRespond(withSuccess("{\"name\":\"23456\"}", MediaType.APPLICATION_JSON));

    provider.start("Read me", "narrations/id.mp3");
    server.verify();
  }

  @Test
  void reportsGooglesDocumentedFiveThousandByteCeiling() {
    assertThat(provider.maxImmediateBytes()).isEqualTo(5000);
  }

  @Test
  void treatsEmptyAudioContentAsRetryable() {
    server.expect(requestTo("https://texttospeech.googleapis.com/v1/text:synthesize"))
        .andRespond(withSuccess("{\"audioContent\":\"\"}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> provider.synthesizeImmediately("Read me"))
        .isInstanceOf(NarrationProviderException.class)
        .hasMessageContaining("no narration audio");
  }

  @Test
  void treatsRejectedSynthesisAsRetryable() {
    server.expect(requestTo("https://texttospeech.googleapis.com/v1/text:synthesize"))
        .andRespond(withBadRequest());

    assertThatThrownBy(() -> provider.synthesizeImmediately("Read me"))
        .isInstanceOf(NarrationProviderException.class)
        .extracting(ex -> ((NarrationProviderException) ex).kind())
        .isEqualTo(FailureKind.SAFE_TO_RETRY);
  }
}
