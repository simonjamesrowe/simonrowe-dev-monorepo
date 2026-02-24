package com.simonrowe.contact;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class RecaptchaService {

  private static final Logger log = LoggerFactory.getLogger(RecaptchaService.class);

  private final RestTemplate restTemplate;
  private final String secretKey;
  private final String verifyUrl;

  public RecaptchaService(
      final RestTemplate restTemplate,
      @Value("${contact.recaptcha.secret-key}") final String secretKey,
      @Value("${contact.recaptcha.verify-url}") final String verifyUrl
  ) {
    this.restTemplate = restTemplate;
    this.secretKey = secretKey;
    this.verifyUrl = verifyUrl;
  }

  @WithSpan
  public void verify(final String token) {
    final String url = verifyUrl + "?secret=" + secretKey + "&response=" + token;
    try {
      @SuppressWarnings("unchecked")
      final Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);
      if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
        log.warn("reCAPTCHA verification failed for token");
        throw new RecaptchaVerificationException("reCAPTCHA verification failed");
      }
      log.debug("reCAPTCHA verification successful");
    } catch (final RecaptchaVerificationException e) {
      throw e;
    } catch (final RestClientException e) {
      log.error("reCAPTCHA service unavailable", e);
      throw new RecaptchaServiceUnavailableException(
          "Verification service temporarily unavailable. Please try again later.", e
      );
    }
  }
}
