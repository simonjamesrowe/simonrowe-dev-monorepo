package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
    properties.setEnvironment("production");
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

  /**
   * Without this, scores land in Langfuse's {@code default} environment while their traces are
   * tagged {@code production}, so every environment-filtered score view reads empty.
   */
  @Test
  void sendsTheConfiguredEnvironmentSoScoresMatchTheirTraces() {
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andExpect(jsonPath("$.environment").value("production"))
        .andRespond(withSuccess());

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 2)));

    server.verify();
  }

  @Test
  void omitsEnvironmentWhenItIsNotConfigured() {
    properties.setEnvironment("  ");
    server.expect(requestTo("http://langfuse.test/api/public/scores"))
        .andExpect(jsonPath("$.environment").doesNotExist())
        .andRespond(withSuccess());

    client().submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 2)));

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

  /**
   * Captures what {@link LangfuseScoreClient}'s constructor logs. {@code submit} is deliberately
   * silent — it runs once per chat turn — so this single startup line is the only signal that
   * distinguishes "scoring is off" from "scoring is broken" when Langfuse shows no scores.
   */
  private List<String> startupLog() {
    Logger logger = (Logger) LoggerFactory.getLogger(LangfuseScoreClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      client();
    } finally {
      logger.detachAppender(appender);
    }
    return appender.list.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage())
        .toList();
  }

  @Test
  void logsOnceAtStartupThatScoringIsEnabled() {
    assertThat(startupLog())
        .singleElement(as(STRING))
        .contains("INFO", "ENABLED", "http://langfuse.test", "production");
  }

  @Test
  void logsAtStartupThatScoringIsDisabledByConfiguration() {
    properties.setScoresEnabled(false);

    assertThat(startupLog())
        .singleElement(as(STRING))
        .contains("INFO", "DISABLED by configuration", "langfuse.scores-enabled=false");
  }

  @Test
  void warnsAtStartupWhenScoringIsEnabledButKeysAreMissing() {
    properties.setSecretKey("   ");

    assertThat(startupLog())
        .singleElement(as(STRING))
        .contains("WARN", "DISABLED IN PRACTICE", "LANGFUSE_SECRET_KEY");
  }

  @Test
  void submissionItselfDoesNotLogPerCall() {
    server.expect(requestTo("http://langfuse.test/api/public/scores")).andRespond(withSuccess());
    LangfuseScoreClient client = client();
    Logger logger = (Logger) LoggerFactory.getLogger(LangfuseScoreClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      client.submit("abc123", List.of(LangfuseScore.numeric("tool-call-count", 1)));
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list).isEmpty();
    server.verify();
  }
}
