package com.simonrowe.portfolio;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The public Portfolio: published projects only. Unauthenticated, like every public read. */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

  private final PortfolioService service;

  public PortfolioController(final PortfolioService service) {
    this.service = service;
  }

  @GetMapping
  public List<PublicProject> list() {
    return service.publicList();
  }

  @GetMapping("/{slug}")
  public PublicProject detail(@PathVariable final String slug) {
    return service.publicDetail(slug).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Portfolio project not found"));
  }
}
