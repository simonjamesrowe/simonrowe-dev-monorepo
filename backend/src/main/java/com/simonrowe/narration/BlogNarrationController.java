package com.simonrowe.narration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/blogs/{blogId}/narration")
@Validated
public class BlogNarrationController {

  private final BlogNarrationService narrationService;

  public BlogNarrationController(final BlogNarrationService narrationService) {
    this.narrationService = narrationService;
  }

  @GetMapping
  public NarrationResponse getStatus(
      @PathVariable final String blogId,
      @RequestParam(required = false) final Long afterVersion,
      @RequestParam(defaultValue = "0") @Min(0) @Max(25) final int waitSeconds
  ) {
    return narrationService.getStatus(blogId, afterVersion, waitSeconds);
  }

  @PostMapping
  public ResponseEntity<NarrationResponse> request(
      @PathVariable final String blogId
  ) {
    BlogNarrationService.RequestResult result = narrationService.request(blogId);
    if (result.response().state() == NarrationResponse.PublicState.UNAVAILABLE) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(result.response());
    }
    return ResponseEntity.status(result.accepted() ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(result.response());
  }
}
