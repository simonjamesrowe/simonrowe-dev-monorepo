package com.simonrowe.contact;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

  @Mock
  private JavaMailSender mailSender;

  @InjectMocks
  private EmailService emailService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(emailService, "fromAddress", "contact-us@simonrowe.dev");
    ReflectionTestUtils.setField(emailService, "toAddress", "simon.rowe@gmail.com");
  }

  private ContactSubmission sampleSubmission() {
    return new ContactSubmission(
        "Jane", "Doe", "jane@example.com",
        "Hello", "Test message body", "http://simonrowe.dev/"
    );
  }

  @Test
  void sendSucceedsWhenMailSenderAcceptsMessage() {
    doNothing().when(mailSender).send(any(SimpleMailMessage.class));

    assertThatCode(() -> emailService.send(sampleSubmission())).doesNotThrowAnyException();
  }

  @Test
  void sendThrowsEmailDeliveryExceptionWhenMailSenderFails() {
    doThrow(new MailSendException("SMTP error"))
        .when(mailSender).send(any(SimpleMailMessage.class));

    assertThatThrownBy(() -> emailService.send(sampleSubmission()))
        .isInstanceOf(EmailDeliveryException.class)
        .hasMessageContaining("Failed to send message");
  }
}
