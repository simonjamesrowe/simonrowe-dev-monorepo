package com.simonrowe.factory.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single authorisation boundary for every internal factory trigger.
 *
 * <p>nginx routes none of these paths, but this JVM also terminates the public webhook, so a
 * routing rule is not what keeps them closed — this class is.
 */
class FactoryTokenAuthenticatorTest {

  private static final String TOKEN = "trigger-token";

  private static FactoryTokenAuthenticator authenticator(final String configured) {
    return new FactoryTokenAuthenticator(
        new CodeReviewProperties(
            new CodeReviewProperties.Github(
                "https://api.github.com", "", "", "", "", Duration.ofSeconds(30)),
            null,
            new CodeReviewProperties.Api(configured),
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
}
