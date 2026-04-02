package com.simonrowe.contact;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recaptcha")
public class RecaptchaController {

  private final RecaptchaService recaptchaService;

  public RecaptchaController(final RecaptchaService recaptchaService) {
    this.recaptchaService = recaptchaService;
  }

  @PostMapping("/verify")
  public ResponseEntity<Void> verify(
      @RequestBody final RecaptchaVerifyRequest request
  ) {
    recaptchaService.verify(request.token());
    return ResponseEntity.ok().build();
  }
}
