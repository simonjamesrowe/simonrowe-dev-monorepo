package com.simonrowe.factory.linear.workflow;

import com.simonrowe.factory.linear.config.LinearTaskQueues;
import com.simonrowe.factory.linear.domain.FiledIssue;
import com.simonrowe.factory.linear.domain.IssueFiling;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.linear.service.IssueFiler;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The executor half of the sink.
 *
 * <p><strong>Do not remove the {@code @ConditionalOnProperty}.</strong> Both {@code
 * software-factory} and {@code deployer} run this image; this annotation is the only thing that
 * stops the {@code deployer} — the container holding a root-equivalent Docker socket — needing a
 * tracker credential. It is evaluated by the component scanner, so declaring this class through an
 * explicit {@code @Bean} method would register it unconditionally and silently ignore the
 * annotation. {@code LinearWorkerRegistrationTest} pins the behaviour.
 */
@Component
@ActivityImpl(taskQueues = LinearTaskQueues.LINEAR)
@ConditionalOnProperty(name = "factory.linear.enabled", havingValue = "true")
public class LinearActivitiesImpl implements LinearActivities {

  private final IssueFiler filer;
  private final LinearGateway gateway;

  /**
   * Creates the activity implementation.
   *
   * @param filer the orchestration this activity is a thin shell over
   */
  public LinearActivitiesImpl(final IssueFiler filer, final LinearGateway gateway) {
    this.filer = filer;
    this.gateway = gateway;
  }

  @Override
  public FiledIssue fileIssue(final IssueFiling filing) {
    try {
      return filer.file(filing);
    } catch (LinearApiException exception) {
      if (exception.retryable()) {
        // Let it out: Temporal's retry policy is the right place to back off a 429 or a 5xx.
        throw exception;
      }
      // WithCause, not the cause-dropping overload: the most opaque non-retryable faults (an
      // unparseable Linear response) carry a wrapped IOException that a triager needs to see.
      throw ApplicationFailure.newNonRetryableFailureWithCause(
          exception.getMessage(), "LINEAR_API_ERROR", exception, filing.producer());
    }
  }

  @Override
  public void attachUrl(final String issueId, final String url, final String title) {
    try {
      boolean alreadyAttached = gateway.issuesForFingerprint(url).stream()
          .anyMatch(issue -> issue.id().equals(issueId));
      if (!alreadyAttached) {
        gateway.attachUrl(issueId, url, title);
      }
    } catch (LinearApiException exception) {
      if (exception.retryable()) {
        throw exception;
      }
      throw ApplicationFailure.newNonRetryableFailureWithCause(
          exception.getMessage(), "LINEAR_API_ERROR", exception, issueId, url);
    }
  }
}
