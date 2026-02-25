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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/profile")
public class AdminProfileController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminProfileController.class);

  private final AdminProfileRepository profileRepository;

  public AdminProfileController(
      final AdminProfileRepository profileRepository
  ) {
    this.profileRepository = profileRepository;
  }

  @GetMapping
  public Profile get() {
    return profileRepository.findAll().stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Profile not found"));
  }

  @PutMapping
  public Profile update(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors =
        validateProfile(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Profile existing = profileRepository.findAll().stream()
        .findFirst()
        .orElse(null);

    Instant now = Instant.now();
    Profile updated = new Profile(
        existing != null ? existing.id() : null,
        (String) body.get("name"),
        (String) body.get("title"),
        (String) body.get("headline"),
        (String) body.get("description"),
        (String) body.get("location"),
        (String) body.get("phoneNumber"),
        (String) body.get("primaryEmail"),
        (String) body.get("secondaryEmail"),
        (String) body.get("profileImage"),
        (String) body.get("sidebarImage"),
        (String) body.get("backgroundImage"),
        (String) body.get("mobileBackgroundImage"),
        existing != null ? existing.createdAt() : now,
        now
    );

    Profile saved = profileRepository.save(updated);
    LOG.info("Updated profile: id={}, user={}",
        saved.id(), jwt.getSubject());
    return saved;
  }

  private List<ValidationErrorResponse.FieldError> validateProfile(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String name = (String) body.get("name");
    String title = (String) body.get("title");
    String primaryEmail = (String) body.get("primaryEmail");

    if (name == null || name.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "name", "Name is required"));
    } else if (name.length() > 100) {
      errors.add(new ValidationErrorResponse.FieldError(
          "name", "Name must not exceed 100 characters"));
    }

    if (title == null || title.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title is required"));
    } else if (title.length() > 200) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title must not exceed 200 characters"));
    }

    if (primaryEmail != null && !primaryEmail.isBlank()
        && !primaryEmail.matches("^[^@]+@[^@]+\\.[^@]+$")) {
      errors.add(new ValidationErrorResponse.FieldError(
          "primaryEmail", "Invalid email format"));
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
