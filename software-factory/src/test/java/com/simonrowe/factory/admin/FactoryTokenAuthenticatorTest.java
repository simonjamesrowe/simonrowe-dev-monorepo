package com.simonrowe.factory.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single authorisation boundary for every internal factory trigger, and now for the
 * read-only per-run detail too.
 *
 * <p>nginx routes none of these paths, but this JVM also terminates the public webhook, so a
 * routing rule is not what keeps them closed — this class is. {@code authenticate} and {@code
 * authenticateRead} check two independent configured values on purpose: a container may be
 * trusted to read titled detail without being trusted to start a deploy, and the two tokens must
 * never be interchangeable.
 */
class FactoryTokenAuthenticatorTest {

  private static final String TOKEN = "trigger-token";
  private static final String READ_TOKEN = "read-token";

  private static FactoryTokenAuthenticator authenticator(final String configuredTrigger) {
    return authenticator(configuredTrigger, READ_TOKEN);
  }

  private static FactoryTokenAuthenticator authenticator(
      final String configuredTrigger, final String configuredRead) {
    return new FactoryTokenAuthenticator(
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(configuredTrigger, configuredRead),
            "https://temporal.test"));
  }

  @Test
  void acceptsTheConfiguredToken() {
    assertThatCode(() -> authenticator(TOKEN).authenticate(TOKEN)).doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongToken() {
    assertThatThrownBy(() -> authenticator(TOKEN).authenticate("wrong"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsMissingToken() {
    assertThatThrownBy(() -> authenticator(TOKEN).authenticate(null))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void failsClosedWhenNoTokenIsConfigured() {
    // Not 401: an unconfigured token means manual triggering was never switched on here, and an
    // empty configured value must never be satisfiable by an empty supplied value.
    assertThatThrownBy(() -> authenticator("").authenticate(""))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThatThrownBy(() -> authenticator(null).authenticate("anything"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void acceptsTheConfiguredReadToken() {
    assertThatCode(() -> authenticator(TOKEN).authenticateRead(READ_TOKEN))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongReadToken() {
    assertThatThrownBy(() -> authenticator(TOKEN).authenticateRead("wrong"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsTheTriggerTokenOnAuthenticateRead() {
    // The two tokens must never be interchangeable, or a container trusted with one is silently
    // trusted with both.
    assertThatThrownBy(() -> authenticator(TOKEN, READ_TOKEN).authenticateRead(TOKEN))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rejectsTheReadTokenOnAuthenticate() {
    assertThatThrownBy(() -> authenticator(TOKEN, READ_TOKEN).authenticate(READ_TOKEN))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void failsClosedWhenNoReadTokenIsConfigured() {
    assertThatThrownBy(() -> authenticator(TOKEN, "").authenticateRead(""))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThatThrownBy(() -> authenticator(TOKEN, null).authenticateRead("anything"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
