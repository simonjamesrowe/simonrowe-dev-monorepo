package com.simonrowe.contact;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ContactController {

  private final ContactService contactService;

  public ContactController(final ContactService contactService) {
    this.contactService = contactService;
  }

  @PostMapping("/contact-us")
  public ResponseEntity<Void> submitContactForm(
      @Valid @RequestBody final ContactRequest request,
      @RequestHeader(value = "Referer", required = false) final String referer
  ) {
    contactService.submit(request, referer);
    return ResponseEntity.ok().build();
  }
}
