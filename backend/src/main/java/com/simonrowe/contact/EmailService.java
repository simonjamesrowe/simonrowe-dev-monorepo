package com.simonrowe.contact;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailService.class);

  private final JavaMailSender mailSender;
  private final String fromAddress;
  private final String toAddress;

  public EmailService(
      final JavaMailSender mailSender,
      @Value("${contact.email.from}") final String fromAddress,
      @Value("${contact.email.to}") final String toAddress
  ) {
    this.mailSender = mailSender;
    this.fromAddress = fromAddress;
    this.toAddress = toAddress;
  }

  @WithSpan
  public void send(final ContactSubmission submission) {
    final SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(fromAddress);
    message.setTo(toAddress);
    message.setSubject(submission.subject());
    message.setText(buildEmailBody(submission));

    try {
      mailSender.send(message);
      log.info("Email sent successfully for submission from {}", submission.email());
    } catch (final MailException e) {
      log.error("Failed to send email via Brevo SMTP", e);
      throw new EmailDeliveryException("Failed to send message. Please try again later.", e);
    }
  }

  private String buildEmailBody(final ContactSubmission submission) {
    return "A message has been sent from the site: " + submission.referrer() + "\n"
        + "Email Address: " + submission.email() + "\n"
        + "Name: " + submission.firstName() + " " + submission.lastName() + "\n"
        + "Content: " + submission.message();
  }
}
