package com.simonrowe.homepage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The hero copy for the public home page. Unauthenticated, like every public read. */
@RestController
@RequestMapping("/api/home-page")
public class HomePageController {

  private final HomePageService service;

  public HomePageController(final HomePageService service) {
    this.service = service;
  }

  @GetMapping
  public HomePageContent get() {
    return service.get();
  }
}
