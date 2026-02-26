package com.simonrowe.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitInterceptorTest {

  private RateLimitInterceptor interceptor;

  @BeforeEach
  void setUp() {
    final RateLimitConfig.BucketConfig chatConfig = new RateLimitConfig.BucketConfig(5);
    final RateLimitConfig.BucketConfig mcpConfig = new RateLimitConfig.BucketConfig(3);
    final RateLimitConfig config = new RateLimitConfig(chatConfig, mcpConfig);
    interceptor = new RateLimitInterceptor(config);
  }

  @Test
  void preHandleReturnsTrueWhenRequestIsWithinChatLimit() throws Exception {
    final MockHttpServletRequest request = chatRequest("10.0.0.1");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final boolean result = interceptor.preHandle(request, response, new Object());

    assertThat(result).isTrue();
    assertThat(response.getStatus()).isNotEqualTo(429);
  }

  @Test
  void preHandleReturnsFalseWhenChatRequestsExceedLimit() throws Exception {
    final String clientIp = "10.0.0.2";

    for (int i = 0; i < 5; i++) {
      final boolean allowed = interceptor.preHandle(
          chatRequest(clientIp), new MockHttpServletResponse(), new Object());
      assertThat(allowed).isTrue();
    }

    final MockHttpServletResponse limitedResponse = new MockHttpServletResponse();
    final boolean result = interceptor.preHandle(
        chatRequest(clientIp), limitedResponse, new Object());

    assertThat(result).isFalse();
    assertThat(limitedResponse.getStatus()).isEqualTo(429);
  }

  @Test
  void preHandleReturnsTrueWhenMcpRequestIsWithinLimit() throws Exception {
    final MockHttpServletRequest request = mcpRequest("10.0.0.3");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    final boolean result = interceptor.preHandle(request, response, new Object());

    assertThat(result).isTrue();
    assertThat(response.getStatus()).isNotEqualTo(429);
  }

  @Test
  void preHandleReturnsFalseWhenMcpRequestsExceedLimit() throws Exception {
    final String clientIp = "10.0.0.4";

    for (int i = 0; i < 3; i++) {
      final boolean allowed = interceptor.preHandle(
          mcpRequest(clientIp), new MockHttpServletResponse(), new Object());
      assertThat(allowed).isTrue();
    }

    final MockHttpServletResponse limitedResponse = new MockHttpServletResponse();
    final boolean result = interceptor.preHandle(
        mcpRequest(clientIp), limitedResponse, new Object());

    assertThat(result).isFalse();
    assertThat(limitedResponse.getStatus()).isEqualTo(429);
  }

  @Test
  void differentIpsHaveIndependentChatLimits() throws Exception {
    final String ipA = "192.168.1.1";
    final String ipB = "192.168.1.2";

    for (int i = 0; i < 5; i++) {
      interceptor.preHandle(chatRequest(ipA), new MockHttpServletResponse(), new Object());
    }

    final MockHttpServletResponse responseForIpB = new MockHttpServletResponse();
    final boolean result = interceptor.preHandle(chatRequest(ipB), responseForIpB, new Object());

    assertThat(result).isTrue();
    assertThat(responseForIpB.getStatus()).isNotEqualTo(429);
  }

  @Test
  void differentIpsHaveIndependentMcpLimits() throws Exception {
    final String ipA = "172.16.0.1";
    final String ipB = "172.16.0.2";

    for (int i = 0; i < 3; i++) {
      interceptor.preHandle(
          mcpRequest(ipA), new MockHttpServletResponse(), new Object());
    }

    final MockHttpServletResponse responseForIpB = new MockHttpServletResponse();
    final boolean result = interceptor.preHandle(mcpRequest(ipB), responseForIpB, new Object());

    assertThat(result).isTrue();
    assertThat(responseForIpB.getStatus()).isNotEqualTo(429);
  }

  @Test
  void preHandleSetsRateLimitRemainingHeaderOnAllowedRequest() throws Exception {
    final MockHttpServletRequest request = chatRequest("10.1.0.1");
    final MockHttpServletResponse response = new MockHttpServletResponse();

    interceptor.preHandle(request, response, new Object());

    assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
    assertThat(Integer.parseInt(response.getHeader("X-RateLimit-Remaining")))
        .isGreaterThanOrEqualTo(0);
  }

  @Test
  void preHandleSetsRateLimitRemainingToZeroWhenLimitExceeded() throws Exception {
    final String clientIp = "10.1.0.2";

    for (int i = 0; i < 5; i++) {
      interceptor.preHandle(chatRequest(clientIp), new MockHttpServletResponse(), new Object());
    }

    final MockHttpServletResponse response = new MockHttpServletResponse();
    interceptor.preHandle(chatRequest(clientIp), response, new Object());

    assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
    assertThat(Long.parseLong(response.getHeader("X-RateLimit-Remaining")))
        .isLessThanOrEqualTo(0);
  }

  @Test
  void preHandleDecrementsRateLimitRemainingWithEachRequest() throws Exception {
    final String clientIp = "10.1.0.3";

    final MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    interceptor.preHandle(chatRequest(clientIp), firstResponse, new Object());
    final int firstRemaining = Integer.parseInt(
        firstResponse.getHeader("X-RateLimit-Remaining"));

    final MockHttpServletResponse secondResponse = new MockHttpServletResponse();
    interceptor.preHandle(chatRequest(clientIp), secondResponse, new Object());
    final int secondRemaining = Integer.parseInt(
        secondResponse.getHeader("X-RateLimit-Remaining"));

    assertThat(secondRemaining).isLessThan(firstRemaining);
  }

  @Test
  void preHandleUsesForwardedForHeaderAsClientIp() throws Exception {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/chat/stream");
    request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
    request.setRemoteAddr("10.0.0.1");

    final MockHttpServletResponse response = new MockHttpServletResponse();

    final boolean result = interceptor.preHandle(request, response, new Object());

    assertThat(result).isTrue();
  }

  @Test
  void preHandleFallsBackToRemoteAddrWhenNoForwardedFor() throws Exception {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/chat/stream");
    request.setRemoteAddr("203.0.113.99");

    final MockHttpServletResponse response = new MockHttpServletResponse();

    final boolean result = interceptor.preHandle(request, response, new Object());

    assertThat(result).isTrue();
  }

  @Test
  void preHandleRoutesChatPathToChatBucket() throws Exception {
    final RateLimitConfig.BucketConfig chatConfig = new RateLimitConfig.BucketConfig(2);
    final RateLimitConfig.BucketConfig mcpConfig = new RateLimitConfig.BucketConfig(100);
    final RateLimitConfig config = new RateLimitConfig(chatConfig, mcpConfig);
    final RateLimitInterceptor separateInterceptor = new RateLimitInterceptor(config);

    final String clientIp = "10.2.0.1";

    for (int i = 0; i < 2; i++) {
      separateInterceptor.preHandle(
          chatRequest(clientIp), new MockHttpServletResponse(), new Object());
    }

    final MockHttpServletResponse response = new MockHttpServletResponse();
    final boolean result = separateInterceptor.preHandle(
        chatRequest(clientIp), response, new Object());

    assertThat(result).isFalse();
    assertThat(response.getStatus()).isEqualTo(429);
  }

  @Test
  void preHandleRoutesMcpPathToMcpBucket() throws Exception {
    final RateLimitConfig.BucketConfig chatConfig = new RateLimitConfig.BucketConfig(100);
    final RateLimitConfig.BucketConfig mcpConfig = new RateLimitConfig.BucketConfig(2);
    final RateLimitConfig config = new RateLimitConfig(chatConfig, mcpConfig);
    final RateLimitInterceptor separateInterceptor = new RateLimitInterceptor(config);

    final String clientIp = "10.2.0.2";

    for (int i = 0; i < 2; i++) {
      separateInterceptor.preHandle(
          mcpRequest(clientIp), new MockHttpServletResponse(), new Object());
    }

    final MockHttpServletResponse response = new MockHttpServletResponse();
    final boolean result = separateInterceptor.preHandle(
        mcpRequest(clientIp), response, new Object());

    assertThat(result).isFalse();
    assertThat(response.getStatus()).isEqualTo(429);
  }

  private static MockHttpServletRequest chatRequest(final String remoteAddr) {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/chat/stream");
    request.setRemoteAddr(remoteAddr);
    return request;
  }

  private static MockHttpServletRequest mcpRequest(final String remoteAddr) {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/mcp/tools");
    request.setRemoteAddr(remoteAddr);
    return request;
  }
}
