package com.simonrowe.homepage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Editing the hero copy. Admin-only through the {@code /api/admin/**} matcher. */
@RestController
@RequestMapping("/api/admin/home-page")
public class HomePageAdminController {

  private static final Logger LOG = LoggerFactory.getLogger(HomePageAdminController.class);

  private final HomePageService service;

  public HomePageAdminController(final HomePageService service) {
    this.service = service;
  }

  @GetMapping
  public HomePageContent get() {
    return service.get();
  }

  @PutMapping
  public HomePageContent update(
      @RequestBody final HomePageContent content,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    HomePageContent saved = service.save(content);
    LOG.info("Home page hero updated by {}", jwt != null ? jwt.getSubject() : "unknown");
    return saved;
  }
}
