package com.simonrowe.admin;

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
@RequestMapping("/api/admin/blogs")
public class AdminBlogController {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminBlogController.class);

  private final AdminBlogRepository blogRepository;
  private final AdminTagRepository tagRepository;
  private final AdminSkillRepository skillRepository;

  public AdminBlogController(
      final AdminBlogRepository blogRepository,
      final AdminTagRepository tagRepository,
      final AdminSkillRepository skillRepository
  ) {
    this.blogRepository = blogRepository;
    this.tagRepository = tagRepository;
    this.skillRepository = skillRepository;
  }

  @GetMapping
  public Page<Map<String, Object>> list(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "20") final int size,
      @RequestParam(required = false) final Boolean published
  ) {
    PageRequest pageRequest = PageRequest.of(page, size,
        Sort.by(Sort.Direction.DESC, "createdDate"));
    Page<Blog> blogs;
    if (published != null) {
      blogs = blogRepository.findByPublished(published, pageRequest);
    } else {
      blogs = blogRepository.findAll(pageRequest);
    }
    return blogs.map(this::toDto);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    List<ValidationErrorResponse.FieldError> errors = validateBlog(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Instant now = Instant.now();
    Blog blog = new Blog(
        null,
        (String) body.get("title"),
        (String) body.get("shortDescription"),
        (String) body.get("content"),
        Boolean.TRUE.equals(body.get("published")),
        (String) body.get("featuredImageUrl"),
        resolveTagsByIds(toStringList(body.get("tags"))),
        resolveSkillsByIds(toStringList(body.get("skills"))),
        now, now, null
    );

    Blog saved = blogRepository.save(blog);
    LOG.info("Created blog: id={}, title={}, user={}",
        saved.id(), saved.title(), jwt.getSubject());
    return toDto(saved);
  }

  @GetMapping("/{id}")
  public Map<String, Object> getById(@PathVariable final String id) {
    Blog blog = findById(id);
    return toDto(blog);
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(
      @PathVariable final String id,
      @RequestBody final Map<String, Object> body,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Blog existing = findById(id);

    List<ValidationErrorResponse.FieldError> errors = validateBlog(body);
    if (!errors.isEmpty()) {
      throw validationException(errors);
    }

    Blog updated = new Blog(
        existing.id(),
        (String) body.get("title"),
        (String) body.get("shortDescription"),
        (String) body.get("content"),
        Boolean.TRUE.equals(body.get("published")),
        (String) body.get("featuredImageUrl"),
        resolveTagsByIds(toStringList(body.get("tags"))),
        resolveSkillsByIds(toStringList(body.get("skills"))),
        existing.createdAt(),
        Instant.now(),
        existing.legacyId()
    );

    Blog saved = blogRepository.save(updated);
    LOG.info("Updated blog: id={}, user={}", id, jwt.getSubject());
    return toDto(saved);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable final String id,
      @AuthenticationPrincipal final Jwt jwt
  ) {
    Blog blog = findById(id);
    blogRepository.delete(blog);
    LOG.info("Deleted blog: id={}, user={}", id, jwt.getSubject());
  }

  private Blog findById(final String id) {
    return blogRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "Blog not found"));
  }

  private Map<String, Object> toDto(final Blog blog) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("id", blog.id());
    dto.put("title", blog.title());
    dto.put("shortDescription", blog.shortDescription());
    dto.put("content", blog.content());
    dto.put("published", blog.published());
    dto.put("featuredImageUrl", blog.featuredImageUrl());
    dto.put("tags", blog.tags() != null
        ? blog.tags().stream().map(Tag::id).toList()
        : List.of());
    dto.put("skills", blog.skills() != null
        ? blog.skills().stream().map(Skill::id).toList()
        : List.of());
    dto.put("createdAt", blog.createdAt());
    dto.put("updatedAt", blog.updatedAt());
    return dto;
  }

  private List<Tag> resolveTagsByIds(final List<String> ids) {
    if (ids == null || ids.isEmpty()) return List.of();
    return ids.stream()
        .map(id -> tagRepository.findById(id).orElse(null))
        .filter(t -> t != null)
        .toList();
  }

  private List<Skill> resolveSkillsByIds(final List<String> ids) {
    if (ids == null || ids.isEmpty()) return List.of();
    return ids.stream()
        .map(id -> skillRepository.findById(id).orElse(null))
        .filter(s -> s != null)
        .toList();
  }

  private List<ValidationErrorResponse.FieldError> validateBlog(
      final Map<String, Object> body
  ) {
    List<ValidationErrorResponse.FieldError> errors = new ArrayList<>();
    String title = (String) body.get("title");
    String shortDesc = (String) body.get("shortDescription");
    String content = (String) body.get("content");
    boolean published = Boolean.TRUE.equals(body.get("published"));

    if (title == null || title.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title is required"));
    } else if (title.length() > 200) {
      errors.add(new ValidationErrorResponse.FieldError(
          "title", "Title must not exceed 200 characters"));
    }

    if (shortDesc == null || shortDesc.isBlank()) {
      errors.add(new ValidationErrorResponse.FieldError(
          "shortDescription", "Short description is required"));
    } else if (shortDesc.length() > 500) {
      errors.add(new ValidationErrorResponse.FieldError(
          "shortDescription",
          "Short description must not exceed 500 characters"));
    }

    if (published) {
      if (content == null || content.isBlank()) {
        errors.add(new ValidationErrorResponse.FieldError(
            "content",
            "Content is required for published posts"));
      }
      Object tags = body.get("tags");
      if (tags == null || (tags instanceof List<?> list && list.isEmpty())) {
        errors.add(new ValidationErrorResponse.FieldError(
            "tags",
            "At least one tag is required for published posts"));
      }
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
