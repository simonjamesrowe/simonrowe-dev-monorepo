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
@RequestMapping("/api/admin/social-media")
public class AdminSocialMediaController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminSocialMediaController.class);

  private final AdminSocialMediaRepository socialMediaRepository;

  public AdminSocialMediaController(
      final AdminSocialMediaRepository socialMediaRepository
  ) {
    this.socialMediaRepository = socialMediaRepository;
  }

  @GetMapping
  public List<SocialMediaLink> list() {
    return socialMediaRepository.findAll();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SocialMediaLink create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors =
        validateSocialMedia(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Instant now = Instant.now();
    SocialMediaLink link = new SocialMediaLink(
        null,
        (String) body.get("type"),
        (String) body.get("link"),
        (String) body.get("name"),
        Boolean.TRUE.equals(body.get("includeOnResume")),
        now, now, null
    );

    SocialMediaLink saved = socialMediaRepository.save(link);
    LOG.info("Created social media link: id={}, type={}, user={}",
        saved.id(), saved.type(), jwt.getSubject());
    return saved;
  }

  @GetMapping("/{id}")
  public SocialMediaLink getById(@PathVariable final String id) {
    return socialMediaRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Social media link not found"));
  }

  @PutMapping("/{id}")
  public SocialMediaLink update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    SocialMediaLink existing = getById(id);

    List<ValidationErrorResponse.FieldError> errors =
        validateSocialMedia(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    SocialMediaLink updated = new SocialMediaLink(
        existing.id(),
        (String) body.get("type"),
        (String) body.get("link"),
        (String) body.get("name"),
        Boolean.TRUE.equals(body.get("includeOnResume")),
        existing.createdAt(),
        Instant.now(),
        existing.legacyId()
    );

    SocialMediaLink saved = socialMediaRepository.save(updated);
    LOG.info("Updated social media link: id={}, user={}",
        id, jwt.getSubject());
    return saved;
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    SocialMediaLink link = getById(id);
    socialMediaRepository.delete(link);
    LOG.info("Deleted social media link: id={}, user={}",
        id, jwt.getSubject());
  }

  private List<ValidationErrorResponse.FieldError> validateSocialMedia(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String type = (String) body.get("type");
    String link = (String) body.get("link");

    if (type == null || type.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "type", "Type is required"));
    }

    if (link == null || link.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "link", "Link is required"));
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
