package com.simonrowe.contact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class RecaptchaServiceTest {

  @Mock
  private RestTemplate restTemplate;

  @InjectMocks
  private RecaptchaService recaptchaService;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(recaptchaService, "secretKey", "test-secret");
    ReflectionTestUtils.setField(
        recaptchaService, "verifyUrl", "https://www.google.com/recaptcha/api/siteverify"
    );
  }

  @Test
  void verifySucceedsWhenGoogleReturnsSuccess() {
    when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
        .thenReturn(Map.of("success", true));

    assertThatCode(() -> recaptchaService.verify("valid-token")).doesNotThrowAnyException();
  }

  @Test
  void verifyThrowsRecaptchaVerificationExceptionWhenSuccessIsFalse() {
    when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
        .thenReturn(Map.of("success", false));

    assertThatThrownBy(() -> recaptchaService.verify("bad-token"))
        .isInstanceOf(RecaptchaVerificationException.class)
        .hasMessage("reCAPTCHA verification failed");
  }

  @Test
  void verifyThrowsRecaptchaServiceUnavailableExceptionWhenGoogleUnreachable() {
    when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
        .thenThrow(new ResourceAccessException("Connection refused"));

    assertThatThrownBy(() -> recaptchaService.verify("token"))
        .isInstanceOf(RecaptchaServiceUnavailableException.class)
        .hasMessageContaining("Verification service temporarily unavailable");
  }
}
