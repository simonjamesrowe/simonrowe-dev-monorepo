package com.simonrowe.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/skills")
public class AdminSkillController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminSkillController.class);

  private final AdminSkillRepository skillRepository;

  public AdminSkillController(final AdminSkillRepository skillRepository) {
    this.skillRepository = skillRepository;
  }

  @GetMapping
  public Page<Skill> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size
  ) {
    return skillRepository.findAllByOrderByOrderAsc(
        PageRequest.of(page, size));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Skill create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors =
        validateSkill(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Instant now = Instant.now();
    Skill skill = new Skill(
        null,
        (String) body.get("name"),
        toDouble(body.get("rating")),
        (String) body.get("description"),
        (String) body.get("image"),
        toInt(body.get("order"), 0),
        now, now, null
    );

    Skill saved = skillRepository.save(skill);
    LOG.info("Created skill: id={}, name={}, user={}",
        saved.id(), saved.name(), jwt.getSubject());
    return saved;
  }

  @GetMapping("/{id}")
  public Skill getById(@PathVariable final String id) {
    return skillRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Skill not found"));
  }

  @PutMapping("/{id}")
  public Skill update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Skill existing = getById(id);

    List<ValidationErrorResponse.FieldError> errors =
        validateSkill(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Skill updated = new Skill(
        existing.id(),
        (String) body.get("name"),
        toDouble(body.get("rating")),
        (String) body.get("description"),
        (String) body.get("image"),
        toInt(body.get("order"), existing.order()),
        existing.createdAt(),
        Instant.now(),
        existing.legacyId()
    );

    Skill saved = skillRepository.save(updated);
    LOG.info("Updated skill: id={}, user={}", id, jwt.getSubject());
    return saved;
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Skill skill = getById(id);
    skillRepository.delete(skill);
    LOG.info("Deleted skill: id={}, user={}", id, jwt.getSubject());
  }

  @PatchMapping("/reorder")
  public void reorder(
      @RequestBody final ReorderRequest request,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<String> orderedIds = request.orderedIds();
    for (int i = 0; i < orderedIds.size(); i++) {
      String skillId = orderedIds.get(i);
      Skill existing = getById(skillId);
      Skill reordered = new Skill(
          existing.id(), existing.name(), existing.rating(),
          existing.description(), existing.image(), i,
          existing.createdAt(), Instant.now(), existing.legacyId()
      );
      skillRepository.save(reordered);
    }
    LOG.info("Reordered {} skills, user={}", orderedIds.size(),
        jwt.getSubject());
  }

  private List<ValidationErrorResponse.FieldError> validateSkill(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String name = (String) body.get("name");
    Double rating = toDouble(body.get("rating"));
    int order = toInt(body.get("order"), 0);

    if (name == null || name.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "name", "Name is required"));
    } else if (name.length() > 100) {
      errors.add(new ValidationErrorResponse.FieldError(
          "name", "Name must not exceed 100 characters"));
    }

    if (rating != null && (rating < 0.0 || rating > 10.0)) {
      errors.add(new ValidationErrorResponse.FieldError(
          "rating", "Rating must be between 0 and 10"));
    }

    if (order < 0) {
      errors.add(new ValidationErrorResponse.FieldError(
          "order", "Order must be non-negative"));
    }

    return errors;
  }

  private Double toDouble(final Object value) {
    if (value instanceof Number num) {
      return num.doubleValue();
    }
    return null;
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
