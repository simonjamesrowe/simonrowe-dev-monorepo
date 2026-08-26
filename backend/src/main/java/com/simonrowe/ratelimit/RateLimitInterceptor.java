package com.simonrowe.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

  private static final String NEWS_PREFIX = "/api/news/";
  private static final String SUMMARY_SUFFIX = "/summary";
  private static final String SUMMARY_NARRATION_SUFFIX = "/summary/narration";

  private final RateLimitConfig config;
  private final ConcurrentHashMap<String, Bucket> chatBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Bucket> mcpBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Bucket> narrationBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Bucket> summaryBuckets = new ConcurrentHashMap<>();

  public RateLimitInterceptor(final RateLimitConfig config) {
    this.config = config;
  }

  @Override
  public boolean preHandle(final HttpServletRequest request,
      final HttpServletResponse response, final Object handler) throws Exception {
    String clientIp = getClientIp(request);
    String path = request.getRequestURI();

    ConcurrentHashMap<String, Bucket> bucketMap;
    int requestsPerMinute;

    if (isSummaryPath(path)) {
      // Only the writes are limited. The summary status endpoint is long-polled — one
      // drawer session is an initial read plus up to four polls — so metering reads out of
      // the same small allowance would 429 a reader in the middle of the generation they
      // just paid for. Reads are public, cheap and idempotent; the POSTs are what spend on
      // the model and the text-to-speech budget.
      if (!HttpMethod.POST.matches(request.getMethod())) {
        return true;
      }
      bucketMap = summaryBuckets;
      requestsPerMinute = config.summary().requestsPerMinute();
    } else if (isNarrationPath(path)) {
      bucketMap = narrationBuckets;
      requestsPerMinute = config.narration().requestsPerMinute();
    } else if (path.startsWith("/mcp")) {
      bucketMap = mcpBuckets;
      requestsPerMinute = config.mcp().requestsPerMinute();
    } else {
      bucketMap = chatBuckets;
      requestsPerMinute = config.chat().requestsPerMinute();
    }

    Bucket bucket = bucketMap.computeIfAbsent(clientIp,
        key -> createBucket(requestsPerMinute));

    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    response.setHeader("X-RateLimit-Remaining",
        String.valueOf(probe.getRemainingTokens()));

    if (probe.isConsumed()) {
      return true;
    }

    long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(
        probe.getNanosToWaitForRefill()) + 1;
    response.setHeader("X-RateLimit-Reset", String.valueOf(waitSeconds));
    response.setHeader("Retry-After", String.valueOf(waitSeconds));
    response.setStatus(429);
    response.setContentType("application/json");
    response.getWriter().write(
        "{\"error\":\"Rate limit exceeded\",\"retryAfter\":" + waitSeconds + "}");
    return false;
  }

  private Bucket createBucket(final int requestsPerMinute) {
    Bandwidth limit = Bandwidth.builder()
        .capacity(requestsPerMinute)
        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
        .build();
    return Bucket.builder()
        .addLimit(limit)
        .build();
  }

  /**
   * Whether the path is a summary request — either {@code /api/news/{id}/summary} or
   * {@code /api/news/{id}/summary/narration}. Both share one bucket: they are the two
   * halves of the same paid-for artefact and a caller should not get a fresh allowance by
   * switching between them.
   *
   * <p>The two suffixes are prefix-overlapping, so the longer one is tested first. Testing
   * {@code /summary} first would strip only {@code "/summary"} from
   * {@code /api/news/{id}/summary/narration}, leaving {@code "{id}/narration"}, which
   * contains a slash and so fails the id check — the narration path would silently fall
   * through to the chat bucket.
   */
  private boolean isSummaryPath(final String path) {
    if (!path.startsWith(NEWS_PREFIX)) {
      return false;
    }
    if (path.endsWith(SUMMARY_NARRATION_SUFFIX)) {
      return isSingleSegment(path, SUMMARY_NARRATION_SUFFIX);
    }
    return path.endsWith(SUMMARY_SUFFIX) && isSingleSegment(path, SUMMARY_SUFFIX);
  }

  /** Whether what sits between {@code /api/news/} and {@code suffix} is one path segment. */
  private boolean isSingleSegment(final String path, final String suffix) {
    String articleId = path.substring(
        NEWS_PREFIX.length(), path.length() - suffix.length());
    return !articleId.isBlank() && !articleId.contains("/");
  }

  private boolean isNarrationPath(final String path) {
    if (!path.startsWith("/api/blogs/") || !path.endsWith("/narration")) {
      return false;
    }
    String blogId = path.substring("/api/blogs/".length(),
        path.length() - "/narration".length());
    return !blogId.isBlank() && !blogId.contains("/");
  }

  private String getClientIp(final HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
