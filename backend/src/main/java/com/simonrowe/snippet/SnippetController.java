package com.simonrowe.snippet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
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

/**
 * Snippets kept from news summaries and blog posts (spec 054). Private to the site owner:
 * everything is under {@code /api/admin}, which {@code SecurityConfig} limits to the admin
 * role, so nothing here is ever served to a visitor, unlike favourites.
 */
@RestController
@RequestMapping("/api/admin/snippets")
@Validated
public class SnippetController {

  private final SnippetService snippetService;

  public SnippetController(final SnippetService snippetService) {
    this.snippetService = snippetService;
  }

  /**
   * One article's or post's snippets, all of them, for the summary drawer or the post page.
   *
   * @param contentId the aggregated article id, or the blog id
   * @param sourceType what the content is; an article summary when omitted
   * @return the snippets, oldest first
   */
  @GetMapping(params = "contentId")
  public List<SnippetResponse> forContent(
      @RequestParam final String contentId,
      @RequestParam(defaultValue = "ARTICLE_SUMMARY") final SnippetSourceType sourceType
  ) {
    return snippetService.forContent(sourceType, contentId);
  }

  /**
   * The console's search.
   *
   * <p>{@code tag} and {@code source} may each be repeated. They are read with
   * {@code getParameterValues}, because {@code @RequestParam List<String>} splits a value
   * sent once on its commas, and a publisher's name can contain one.
   *
   * @param q free text
   * @param from kept on or after this day
   * @param to kept on or before this day
   * @param sort {@code newest} or {@code article}
   * @param page the page
   * @param size snippets per page
   * @param request read for the repeated {@code tag} and {@code source} values
   * @return the matching snippets
   */
  @GetMapping
  public Page<SnippetResponse> search(
      @RequestParam(required = false) final String q,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
      final LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
      final LocalDate to,
      @RequestParam(defaultValue = SnippetSearch.SORT_NEWEST) final String sort,
      @RequestParam(defaultValue = "0") @Min(0) final int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) final int size,
      final HttpServletRequest request
  ) {
    SnippetSearch search = new SnippetSearch(
        q, values(request, "tag"), values(request, "source"), from, to, sort);
    return snippetService.search(search, PageRequest.of(page, size));
  }

  /**
   * How many snippets each article has, for the badges on the news cards.
   *
   * @param sourceType which kind of content to count; article summaries when omitted
   * @return content id to count, only content with at least one
   */
  @GetMapping("/counts")
  public Map<String, Long> counts(
      @RequestParam(defaultValue = "ARTICLE_SUMMARY") final SnippetSourceType sourceType
  ) {
    return snippetService.countsByContent(sourceType);
  }

  /**
   * Every tag in use, most used first.
   *
   * @return the tags with their counts
   */
  @GetMapping("/tags")
  public List<TagCount> tags() {
    return snippetService.tags();
  }

  /**
   * Every publisher a snippet came from, {@code simonrowe.dev} for blog posts.
   *
   * @return the source names
   */
  @GetMapping("/sources")
  public List<String> sources() {
    return snippetService.sources();
  }

  /**
   * Keeps a passage.
   *
   * @param jwt the signed-in owner
   * @param request the passage, note and tags
   * @return the stored snippet
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SnippetResponse create(
      @AuthenticationPrincipal final Jwt jwt,
      @RequestBody final CreateSnippetRequest request
  ) {
    return snippetService.create(request, jwt.getSubject());
  }

  /**
   * Replaces a snippet's note and tags.
   *
   * @param id the snippet
   * @param request the note and tags
   * @return the updated snippet
   */
  @PutMapping("/{id}")
  public SnippetResponse update(
      @PathVariable final String id,
      @RequestBody final UpdateSnippetRequest request
  ) {
    return snippetService.update(id, request);
  }

  /**
   * Deletes a snippet.
   *
   * @param id the snippet
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable final String id) {
    snippetService.delete(id);
  }

  private static List<String> values(final HttpServletRequest request, final String name) {
    String[] values = request.getParameterValues(name);
    return values == null ? List.of() : List.of(values);
  }
}
