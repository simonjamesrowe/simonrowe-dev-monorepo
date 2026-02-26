package com.simonrowe.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitConfig(
    BucketConfig chat,
    BucketConfig mcp
) {

  public record BucketConfig(int requestsPerMinute) {
  }
}
