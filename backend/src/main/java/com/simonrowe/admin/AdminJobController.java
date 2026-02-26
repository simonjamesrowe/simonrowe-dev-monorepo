package com.simonrowe.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/jobs")
public class AdminJobController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminJobController.class);

  private final AdminJobRepository jobRepository;

  public AdminJobController(final AdminJobRepository jobRepository) {
    this.jobRepository = jobRepository;
  }

  @GetMapping
  public Page<Job> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final Boolean education
  ) {
    PageRequest pageRequest = PageRequest.of(page, size,
        Sort.by(Sort.Direction.DESC, "startDate"));
    if (education != null) {
      return jobRepository.findByEducation(education, pageRequest);
    }
    return jobRepository.findAll(pageRequest);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Job create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors = validateJob(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Instant now = Instant.now();
    Job job = buildJob(null, body, now, now, null);
    Job saved = jobRepository.save(job);
    LOG.info("Created job: id={}, title={}, user={}",
        saved.id(), saved.title(), jwt.getSubject());
    return saved;
  }

  @GetMapping("/{id}")
  public Job getById(@PathVariable final String id) {
    return jobRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Job not found"));
  }

  @PutMapping("/{id}")
  public Job update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Job existing = getById(id);

    List<ValidationErrorResponse.FieldError> errors = validateJob(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Job updated = buildJob(existing.id(), body,
        existing.createdAt(), Instant.now(), existing.legacyId());
    Job saved = jobRepository.save(updated);
    LOG.info("Updated job: id={}, user={}", id, jwt.getSubject());
    return saved;
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Job job = getById(id);
    jobRepository.delete(job);
    LOG.info("Deleted job: id={}, user={}", id, jwt.getSubject());
  }

  @SuppressWarnings("unchecked")
  private Job buildJob(
      final String id,
      final Map<String, Object> body,
      final Instant createdAt,
      final Instant updatedAt,
      final String legacyId
  ) {
    List<String> skills = body.get("skills") instanceof List<?> list
        ? list.stream().map(Object::toString).toList()
        : List.of();

    return new Job(
        id,
        (String) body.get("title"),
        (String) body.get("company"),
        (String) body.get("companyUrl"),
        (String) body.get("companyImage"),
        (String) body.get("startDate"),
        (String) body.get("endDate"),
        (String) body.get("location"),
        (String) body.get("shortDescription"),
        (String) body.get("longDescription"),
        Boolean.TRUE.equals(body.get("education")),
        body.get("includeOnResume") == null
            || Boolean.TRUE.equals(body.get("includeOnResume")),
        skills,
        createdAt, updatedAt, legacyId
    );
  }

  private List<ValidationErrorResponse.FieldError> validateJob(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String title = (String) body.get("title");
    String company = (String) body.get("company");
    String startDate = (String) body.get("startDate");
    String endDate = (String) body.get("endDate");

    if (title == null || title.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title is required"));
    } else if (title.length() > 200) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title must not exceed 200 characters"));
    }

    if (company == null || company.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "company", "Company is required"));
    } else if (company.length() > 200) {
      errors.add(new ValidationErrorResponse.FieldError(
          "company", "Company must not exceed 200 characters"));
    }

    if (startDate == null || startDate.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "startDate", "Start date is required"));
    } else {
      try {
        LocalDate start = LocalDate.parse(startDate);
        if (endDate != null && !endDate.isBlank()) {
          LocalDate end = LocalDate.parse(endDate);
          if (end.isBefore(start)) {
            errors.add(new ValidationErrorResponse.FieldError(
                "endDate",
                "End date must be after start date"));
          }
        }
      } catch (DateTimeParseException e) {
        errors.add(new ValidationErrorResponse.FieldError(
            "startDate", "Invalid date format"));
      }
    }

    String shortDesc = (String) body.get("shortDescription");
    if (shortDesc == null || shortDesc.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "shortDescription", "Short description is required"));
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
