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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/tags")
public class AdminTagController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminTagController.class);

  private final AdminTagRepository tagRepository;

  public AdminTagController(final AdminTagRepository tagRepository) {
    this.tagRepository = tagRepository;
  }

  @GetMapping
  public List<Tag> list() {
    return tagRepository.findAll();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Tag create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors = validateTag(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    String name = (String) body.get("name");
    tagRepository.findByNameIgnoreCase(name).ifPresent(existing -> {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Tag with name '" + name + "' already exists");
    });

    Instant now = Instant.now();
    Tag tag = new Tag(null, name, now, now, null);

    Tag saved = tagRepository.save(tag);
    LOG.info("Created tag: id={}, name={}, user={}",
        saved.id(), saved.name(), jwt.getSubject());
    return saved;
  }

  @PostMapping("/bulk")
  @ResponseStatus(HttpStatus.CREATED)
  @SuppressWarnings("unchecked")
  public List<Tag> bulkCreate(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Object namesObj = body.get("names");
    if (!(namesObj instanceof List<?> namesList)
        || namesList.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "names array is required");
    }

    Instant now = Instant.now();
    List<Tag> created = new ArrayList<>();

    for (Object nameObj : namesList) {
      String name = nameObj.toString().trim();
      if (name.isBlank()) {
        continue;
      }
      if (tagRepository.findByNameIgnoreCase(name).isEmpty()) {
        Tag tag = new Tag(null, name, now, now, null);
        created.add(tagRepository.save(tag));
      }
    }

    LOG.info("Bulk created {} tags, user={}",
        created.size(), jwt.getSubject());
    return created;
  }

  @GetMapping("/{id}")
  public Tag getById(@PathVariable final String id) {
    return tagRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Tag not found"));
  }

  @PutMapping("/{id}")
  public Tag update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Tag existing = getById(id);

    List<ValidationErrorResponse.FieldError> errors = validateTag(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    String name = (String) body.get("name");
    tagRepository.findByNameIgnoreCase(name)
        .filter(found -> !found.id().equals(id))
        .ifPresent(found -> {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "Tag with name '" + name + "' already exists");
        });

    Tag updated = new Tag(
        existing.id(),
        name,
        existing.createdAt(),
        Instant.now(),
        existing.legacyId()
    );

    Tag saved = tagRepository.save(updated);
    LOG.info("Updated tag: id={}, user={}", id, jwt.getSubject());
    return saved;
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Tag tag = getById(id);
    tagRepository.delete(tag);
    LOG.info("Deleted tag: id={}, user={}", id, jwt.getSubject());
  }

  private List<ValidationErrorResponse.FieldError> validateTag(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String name = (String) body.get("name");

    if (name == null || name.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "name", "Name is required"));
    } else if (name.length() > 50) {
      errors.add(new ValidationErrorResponse.FieldError(
          "name", "Name must not exceed 50 characters"));
    }

    return errors;
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
