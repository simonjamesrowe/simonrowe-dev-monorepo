package com.simonrowe.admin;

import com.simonrowe.embedding.EmbeddingService;
import com.simonrowe.events.ContentChangeEvent.ContentType;
import com.simonrowe.events.ContentChangePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
@RequestMapping("/api/admin/code-examples")
public class AdminCodeExampleController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminCodeExampleController.class);
  private static final int MAX_TITLE_LENGTH = 200;
  private static final int MAX_DESCRIPTION_LENGTH = 2000;
  private static final int MAX_LANGUAGE_LENGTH = 50;

  private final AdminCodeExampleRepository codeExampleRepository;
  private final AdminSkillRepository skillRepository;
  private final ContentChangePublisher contentChangePublisher;
  private final EmbeddingService embeddingService;

  public AdminCodeExampleController(
      final AdminCodeExampleRepository codeExampleRepository,
      final AdminSkillRepository skillRepository,
      final ContentChangePublisher contentChangePublisher,
      final EmbeddingService embeddingService
  ) {
    this.codeExampleRepository = codeExampleRepository;
    this.skillRepository = skillRepository;
    this.contentChangePublisher = contentChangePublisher;
    this.embeddingService = embeddingService;
  }

  @GetMapping
  public Page<Map<String, Object>> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final String skill,
      @RequestParam(required = false) final String language,
      @RequestParam(required = false) final String search
  ) {
    PageRequest pageRequest = PageRequest.of(page, size,
        Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<CodeExample> results;
    if (skill != null && !skill.isBlank()) {
      results = codeExampleRepository.findBySkillsId(skill, pageRequest);
    } else if (language != null && !language.isBlank()) {
      results = codeExampleRepository.findByLanguage(language, pageRequest);
    } else if (search != null && !search.isBlank()) {
      results = codeExampleRepository
          .findByTitleContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
              search, search, pageRequest);
    } else {
      results = codeExampleRepository.findAll(pageRequest);
    }
    return results.map(this::toDto);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors = validate(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Instant now = Instant.now();
    CodeExample example = new CodeExample(
        null,
        (String) body.get("title"),
        (String) body.get("description"),
        (String) body.get("language"),
        (String) body.get("code"),
        resolveSkillsByIds(toStringList(body.get("skills"))),
        now, now
    );

    CodeExample saved = codeExampleRepository.save(example);
    LOG.info("Created code example: id={}, title={}, user={}",
        saved.id(), saved.title(), jwt.getSubject());
    contentChangePublisher.publishCreated(ContentType.CODE_EXAMPLE, saved.id());
    embedCodeExample(saved);
    return toDto(saved);
  }

  @GetMapping("/{id}")
  public Map<String, Object> getById(@PathVariable final String id) {
    return toDto(findById(id));
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    CodeExample existing = findById(id);

    List<ValidationErrorResponse.FieldError> errors = validate(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    CodeExample updated = new CodeExample(
        existing.id(),
        (String) body.get("title"),
        (String) body.get("description"),
        (String) body.get("language"),
        (String) body.get("code"),
        resolveSkillsByIds(toStringList(body.get("skills"))),
        existing.createdAt(),
        Instant.now()
    );

    CodeExample saved = codeExampleRepository.save(updated);
    LOG.info("Updated code example: id={}, user={}", id, jwt.getSubject());
    contentChangePublisher.publishUpdated(ContentType.CODE_EXAMPLE, saved.id());
    embedCodeExample(saved);
    return toDto(saved);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    CodeExample example = findById(id);
    codeExampleRepository.delete(example);
    embeddingService.removeContent(id);
    contentChangePublisher.publishDeleted(ContentType.CODE_EXAMPLE, id);
    LOG.info("Deleted code example: id={}, user={}", id, jwt.getSubject());
  }

  private void embedCodeExample(final CodeExample example) {
    embeddingService.embedCodeExample(example);
  }

  private CodeExample findById(final String id) {
    return codeExampleRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Code example not found"));
  }

  private Map<String, Object> toDto(final CodeExample example) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", example.id());
    dto.put("title", example.title());
    dto.put("description", example.description());
    dto.put("language", example.language());
    dto.put("code", example.code());
    dto.put("skills", example.skills() != null
        ? example.skills().stream().map(Skill::id).toList()
        : List.of());
    dto.put("createdAt", example.createdAt());
    dto.put("updatedAt", example.updatedAt());
    return dto;
  }

  private List<Skill> resolveSkillsByIds(final List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream()
        .map(id -> skillRepository.findById(id).orElse(null))
        .filter(s -> s != null)
        .toList();
  }

  private List<ValidationErrorResponse.FieldError> validate(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String title = (String) body.get("title");
    String description = (String) body.get("description");
    String language = (String) body.get("language");

    if (title == null || title.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title is required"));
    } else if (title.length() > MAX_TITLE_LENGTH) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title must not exceed " + MAX_TITLE_LENGTH + " characters"));
    }

    if (description == null || description.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "description", "Description is required"));
    } else if (description.length() > MAX_DESCRIPTION_LENGTH) {
      errors.add(new ValidationErrorResponse.FieldError(
          "description",
          "Description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters"));
    }

    if (language == null || language.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "language", "Language is required"));
    } else if (language.length() > MAX_LANGUAGE_LENGTH) {
      errors.add(new ValidationErrorResponse.FieldError(
          "language",
          "Language must not exceed " + MAX_LANGUAGE_LENGTH + " characters"));
    }

    String code = (String) body.get("code");
    if (code == null || code.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "code", "Code is required"));
    }

    return errors;
  }

  @SuppressWarnings("unchecked")
  private List<String> toStringList(final Object value) {
    if (value instanceof List<?> list) {
      return list.stream()
          .map(Object::toString)
          .toList();
    }
    return List.of();
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
