package com.simonrowe.factoryadmin;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Submits and reads jobs on software-factory's content-writing queue.
 *
 * <p>Owner-scheduled background jobs only. The factory writes with Claude Code on Simon's
 * subscription, whose terms cover ordinary individual use and forbid serving other users through
 * it, so nothing a site visitor triggers may ever reach this client. Every caller must also keep
 * its own fallback: the subscription's rate limits are shared with interactive use and with the
 * code reviewer.
 *
 * <p>Its own short timeout rather than {@link FactoryAdminProperties#timeout()}: that one is sized
 * for a cold flow snapshot, and a scheduled sweep waiting thirty seconds per release on a factory
 * that is down would hold the scheduler thread for minutes before falling back. Both endpoints
 * answer from Temporal alone and never wait for the job itself.
 */
@Component
public class ContentWritingClient {

  private static final String TOKEN_HEADER = "X-Factory-Token";
  private static final String JOBS_PATH = "/api/writing/jobs";

  private final RestClient factory;
  private final String token;

  /**
   * Creates the client.
   *
   * @param properties the factory base URL and trigger token shared with the admin proxy
   * @param timeout the connect and read timeout for each call
   */
  public ContentWritingClient(
      final FactoryAdminProperties properties,
      @Value("${factory-admin.writing-timeout:5s}") final Duration timeout) {
    this.factory =
        RestClient.builder()
            .baseUrl(properties.factoryBaseUrl())
            .requestFactory(
                new RestTemplateBuilder()
                    .connectTimeout(timeout)
                    .readTimeout(timeout)
                    .buildRequestFactory())
            .build();
    this.token = properties.triggerToken();
  }

  /**
   * Reads a job.
   *
   * @param jobId the job id it was (or would be) submitted under
   * @return its state, with the text once completed
   * @throws RestClientException when the factory is unreachable or refuses the call
   */
  public WritingJob status(final String jobId) {
    return required(
        factory.get().uri(JOBS_PATH + "/{jobId}", jobId).header(TOKEN_HEADER, token)
            .retrieve().body(WritingJob.class));
  }

  /**
   * Submits a job. Idempotent on the factory side: a running or completed job is returned as it
   * stands rather than started again. A failed one is restarted, which is why a caller wanting to
   * fall back after a failure reads {@link #status(String)} first.
   *
   * @param jobId the caller's idempotency key
   * @param prompt the complete instruction
   * @return the job's state after the submit
   * @throws RestClientException when the factory is unreachable or refuses the call
   */
  public WritingJob submit(final String jobId, final String prompt) {
    return required(
        factory.post().uri(JOBS_PATH).header(TOKEN_HEADER, token)
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(Map.of("jobId", jobId, "prompt", prompt))
            .retrieve().body(WritingJob.class));
  }

  private static WritingJob required(final WritingJob job) {
    if (job == null || job.status() == null) {
      throw new RestClientException("The Software Factory answered a writing call with no state");
    }
    return job;
  }

  /** A job's state as the factory reports it. */
  public enum State {
    RUNNING,
    COMPLETED,
    FAILED,
    NOT_FOUND
  }

  /**
   * One job.
   *
   * @param jobId the job id
   * @param status its state
   * @param text the written text, present only when completed
   */
  public record WritingJob(String jobId, State status, String text) {
  }
}
