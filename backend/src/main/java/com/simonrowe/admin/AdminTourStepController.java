package com.simonrowe.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/tour-steps")
public class AdminTourStepController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminTourStepController.class);

  private final AdminTourStepRepository tourStepRepository;

  public AdminTourStepController(
      final AdminTourStepRepository tourStepRepository
  ) {
    this.tourStepRepository = tourStepRepository;
  }

  @GetMapping
  public List<TourStep> list() {
    return tourStepRepository.findAllByOrderByOrderAsc();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TourStep create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors =
        validateTourStep(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Instant now = Instant.now();
    TourStep step = new TourStep(
        null,
        (String) body.get("title"),
        (String) body.get("selector"),
        (String) body.get("description"),
        (String) body.get("titleImage"),
        (String) body.get("position"),
        toInt(body.get("order"), 0),
        now, now, null
    );

    TourStep saved = tourStepRepository.save(step);
    LOG.info("Created tour step: id={}, title={}, user={}",
        saved.id(), saved.title(), jwt.getSubject());
    return saved;
  }

  @GetMapping("/{id}")
  public TourStep getById(@PathVariable final String id) {
    return tourStepRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Tour step not found"));
  }

  @PutMapping("/{id}")
  public TourStep update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    TourStep existing = getById(id);

    List<ValidationErrorResponse.FieldError> errors =
        validateTourStep(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    TourStep updated = new TourStep(
        existing.id(),
        (String) body.get("title"),
        (String) body.get("selector"),
        (String) body.get("description"),
        (String) body.get("titleImage"),
        (String) body.get("position"),
        toInt(body.get("order"), existing.order()),
        existing.createdAt(),
        Instant.now(),
        existing.legacyId()
    );

    TourStep saved = tourStepRepository.save(updated);
    LOG.info("Updated tour step: id={}, user={}", id, jwt.getSubject());
    return saved;
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    TourStep step = getById(id);
    tourStepRepository.delete(step);
    LOG.info("Deleted tour step: id={}, user={}", id, jwt.getSubject());
  }

  @PatchMapping("/reorder")
  public void reorder(
      @RequestBody final ReorderRequest request,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<String> orderedIds = request.orderedIds();
    for (int i = 0; i < orderedIds.size(); i++) {
      String stepId = orderedIds.get(i);
      TourStep existing = getById(stepId);
      TourStep reordered = new TourStep(
          existing.id(), existing.title(), existing.selector(),
          existing.description(), existing.titleImage(),
          existing.position(), i,
          existing.createdAt(), Instant.now(), existing.legacyId()
      );
      tourStepRepository.save(reordered);
    }
    LOG.info("Reordered {} tour steps, user={}",
        orderedIds.size(), jwt.getSubject());
  }

  private List<ValidationErrorResponse.FieldError> validateTourStep(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String title = (String) body.get("title");
    String selector = (String) body.get("selector");
    int order = toInt(body.get("order"), 0);

    if (title == null || title.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title is required"));
    } else if (title.length() > 100) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title must not exceed 100 characters"));
    }

    if (selector == null || selector.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "selector", "Selector is required"));
    }

    if (order < 0) {
      errors.add(new ValidationErrorResponse.FieldError(
          "order", "Order must be non-negative"));
    }

    return errors;
  }

  private int toInt(final Object value, final int defaultValue) {
    if (value instanceof Number num) {
      return num.intValue();
    }
    return defaultValue;
  }

  private ResponseStatusException validationException(
      final List<ValidationErrorResponse.FieldError> errors
  ) {
    String message = errors.stream()
        .map(ValidationErrorResponse.FieldError::message)
        .reduce((a, b) -> a + "; " + b)
        .orElse("Validation failed");
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
