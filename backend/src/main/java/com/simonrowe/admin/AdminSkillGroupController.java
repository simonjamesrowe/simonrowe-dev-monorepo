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
@RequestMapping("/api/admin/skill-groups")
public class AdminSkillGroupController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminSkillGroupController.class);

  private final AdminSkillGroupRepository skillGroupRepository;

  public AdminSkillGroupController(
      final AdminSkillGroupRepository skillGroupRepository
  ) {
    this.skillGroupRepository = skillGroupRepository;
  }

  @GetMapping
  public Page<SkillGroup> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size
  ) {
    return skillGroupRepository.findAllByOrderByOrderAsc(
        PageRequest.of(page, size));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SkillGroup create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors =
        validateSkillGroup(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Instant now = Instant.now();
    SkillGroup group = new SkillGroup(
        null,
        (String) body.get("name"),
        toDouble(body.get("rating")),
        (String) body.get("description"),
        (String) body.get("image"),
        toInt(body.get("order"), 0),
        toStringList(body.get("skills")),
        now, now, null
    );

    SkillGroup saved = skillGroupRepository.save(group);
    LOG.info("Created skill group: id={}, name={}, user={}",
        saved.id(), saved.name(), jwt.getSubject());
    return saved;
  }

  @GetMapping("/{id}")
  public SkillGroup getById(@PathVariable final String id) {
    return skillGroupRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Skill group not found"));
  }

  @PutMapping("/{id}")
  public SkillGroup update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    SkillGroup existing = getById(id);

    List<ValidationErrorResponse.FieldError> errors =
        validateSkillGroup(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    SkillGroup updated = new SkillGroup(
        existing.id(),
        (String) body.get("name"),
        toDouble(body.get("rating")),
        (String) body.get("description"),
        (String) body.get("image"),
        toInt(body.get("order"), existing.order()),
        toStringList(body.get("skills")),
        existing.createdAt(),
        Instant.now(),
        existing.legacyId()
    );

    SkillGroup saved = skillGroupRepository.save(updated);
    LOG.info("Updated skill group: id={}, user={}", id,
        jwt.getSubject());
    return saved;
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    SkillGroup group = getById(id);
    skillGroupRepository.delete(group);
    LOG.info("Deleted skill group: id={}, user={}", id,
        jwt.getSubject());
  }

  @PatchMapping("/reorder")
  public void reorder(
      @RequestBody final ReorderRequest request,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<String> orderedIds = request.orderedIds();
    for (int i = 0; i < orderedIds.size(); i++) {
      String groupId = orderedIds.get(i);
      SkillGroup existing = getById(groupId);
      SkillGroup reordered = new SkillGroup(
          existing.id(), existing.name(), existing.rating(),
          existing.description(), existing.image(), i,
          existing.skills(), existing.createdAt(),
          Instant.now(), existing.legacyId()
      );
      skillGroupRepository.save(reordered);
    }
    LOG.info("Reordered {} skill groups, user={}",
        orderedIds.size(), jwt.getSubject());
  }

  @SuppressWarnings("unchecked")
  private List<String> toStringList(final Object value) {
    if (value instanceof List<?> list) {
      return list.stream().map(Object::toString).toList();
    }
    return List.of();
  }

  private List<ValidationErrorResponse.FieldError> validateSkillGroup(
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
