package com.simonrowe.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

  private final RateLimitConfig config;
  private final ConcurrentHashMap<String, Bucket> chatBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Bucket> mcpBuckets = new ConcurrentHashMap<>();

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

    if (path.startsWith("/mcp")) {
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

  private String getClientIp(final HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
