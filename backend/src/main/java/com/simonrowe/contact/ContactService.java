package com.simonrowe.contact;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ContactService {

  private static final Logger log = LoggerFactory.getLogger(ContactService.class);

  private final RecaptchaService recaptchaService;
  private final EmailService emailService;

  public ContactService(
      final RecaptchaService recaptchaService,
      final EmailService emailService
  ) {
    this.recaptchaService = recaptchaService;
    this.emailService = emailService;
  }

  @WithSpan
  public void submit(final ContactRequest request, final String referrer) {
    log.info("Contact submission received from {}", request.email());

    recaptchaService.verify(request.recaptchaToken());
    log.info("reCAPTCHA verification passed for {}", request.email());

    final ContactSubmission submission = new ContactSubmission(
        request.firstName(),
        request.lastName(),
        request.email(),
        request.subject(),
        request.message(),
        referrer
    );

    emailService.send(submission);
    log.info("Email dispatched successfully for contact from {}", request.email());
  }
}
