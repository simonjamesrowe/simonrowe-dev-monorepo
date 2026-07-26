package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LangfuseScoreClientTest {

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private LangfuseProperties properties;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    properties = new LangfuseProperties();
    properties.setHost("http://langfuse.test");
    properties.setPublicKey("pk-test");
    properties.setSecretKey("sk-test");
    properties.setScoresEnabled(true);
  }

  private LangfuseScoreClient client() {
    return new LangfuseScoreClient(builder, properties, Runnable::run);
  }

  @Test
  void postsEachScoreWithBasicAuthAndCorrectBody() {
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Basic cGstdGVzdDpzay10ZXN0"))
        .andExpect(jsonPath("$.traceId").value("abc123"))
        .andExpect(jsonPath("$.name").value("guardrail"))
        .andExpect(jsonPath("$.value").value("SAFE"))
        .andExpect(jsonPath("$.dataType").value("CATEGORICAL"))
        .andRespond(withSuccess());

    client().submit("abc123", List.of(LangfuseScore.categorical("guardrail", "SAFE")));

    server.verify();
  }

  @Test
  void encodesBooleanScoresAsOneOrZero() {
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andExpect(jsonPath("$.value").value(1))
        .andExpect(jsonPath("$.dataType").value("BOOLEAN"))
        .andRespond(withSuccess());

    client().submit("abc123", List.of(LangfuseScore.bool("error", true)));

    server.verify();
  }

  @Test
  void serverErrorIsSwallowedAndNeverPropagates() {
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andRespond(withServerError());

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 3)));

    server.verify();
  }

  @Test
  void submitsNothingWhenScoresAreDisabled() {
    properties.setScoresEnabled(false);

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 1)));

    server.verify();
  }

  @Test
  void submitsNothingWhenKeysAreMissing() {
    properties.setPublicKey(null);

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 1)));

    server.verify();
  }

  @Test
  void submitsNothingWhenTraceIdIsNull() {
    client().submit(null, List.of(LangfuseScore.numeric("tool-call-count", 1)));

    server.verify();
  }

  @Test
  void booleanFalseIsEncodedAsZero() {
    assertThat(LangfuseScore.bool("error", false).value()).isEqualTo(0);
  }
}
