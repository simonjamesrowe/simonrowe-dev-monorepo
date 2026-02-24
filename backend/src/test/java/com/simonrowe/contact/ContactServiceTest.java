package com.simonrowe.contact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContactServiceTest {

  @Mock
  private RecaptchaService recaptchaService;

  @Mock
  private EmailService emailService;

  @InjectMocks
  private ContactService contactService;

  private ContactRequest validRequest() {
    return new ContactRequest(
        "Jane", "Doe", "jane@example.com",
        "Hello", "Test message", "valid-token"
    );
  }

  @Test
  void submitSucceedsWhenRecaptchaPassesAndEmailSent() {
    doNothing().when(recaptchaService).verify(eq("valid-token"));
    doNothing().when(emailService).send(any());

    assertThatCode(() -> contactService.submit(validRequest(), "http://simonrowe.dev/"))
        .doesNotThrowAnyException();

    verify(recaptchaService).verify("valid-token");
    verify(emailService).send(any());
  }

  @Test
  void submitThrowsRecaptchaVerificationExceptionWhenTokenInvalid() {
    doThrow(new RecaptchaVerificationException("reCAPTCHA verification failed"))
        .when(recaptchaService).verify(eq("bad-token"));

    final ContactRequest request = new ContactRequest(
        "Jane", "Doe", "jane@example.com", "Hello", "Message", "bad-token"
    );

    assertThatThrownBy(() -> contactService.submit(request, null))
        .isInstanceOf(RecaptchaVerificationException.class);
  }

  @Test
  void submitThrowsRecaptchaServiceUnavailableExceptionWhenServiceDown() {
    doThrow(new RecaptchaServiceUnavailableException("Service unavailable", new Exception()))
        .when(recaptchaService).verify(any());

    assertThatThrownBy(() -> contactService.submit(validRequest(), null))
        .isInstanceOf(RecaptchaServiceUnavailableException.class);
  }

  @Test
  void submitThrowsEmailDeliveryExceptionWhenEmailFails() {
    doNothing().when(recaptchaService).verify(any());
    doThrow(new EmailDeliveryException("Failed to send", new Exception()))
        .when(emailService).send(any());

    assertThatThrownBy(() -> contactService.submit(validRequest(), null))
        .isInstanceOf(EmailDeliveryException.class);
  }

  @Test
  void submitPassesReferrerHeaderToSubmission() {
    doNothing().when(recaptchaService).verify(any());
    doNothing().when(emailService).send(any());

    contactService.submit(validRequest(), "http://simonrowe.dev/contact");

    verify(emailService).send(
        new ContactSubmission(
            "Jane", "Doe", "jane@example.com",
            "Hello", "Test message", "http://simonrowe.dev/contact"
        )
    );
  }

  @Test
  void submitPassesNullReferrerWhenHeaderAbsent() {
    doNothing().when(recaptchaService).verify(any());
    doNothing().when(emailService).send(any());

    contactService.submit(validRequest(), null);

    verify(emailService).send(
        new ContactSubmission(
            "Jane", "Doe", "jane@example.com",
            "Hello", "Test message", null
        )
    );
  }
}
