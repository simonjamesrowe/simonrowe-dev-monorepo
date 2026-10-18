package com.simonrowe.snippet;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ArticleQueryService;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryService;
import com.simonrowe.summary.SummaryStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Keeps, edits, finds and deletes snippets (spec 054). */
@Service
public class SnippetService {

  private static final List<String> SEARCHED_FIELDS =
      List.of("exact", "note", "tags", "articleTitle", "sourceName");
  private static final String CONTENT_ID = "contentId";
  private static final String CREATED_AT = "createdAt";
  private static final String COUNT = "count";

  private final SnippetRepository repository;
  private final MongoTemplate mongoTemplate;
  private final AggregatedArticleRepository articleRepository;
  private final ArticleSummaryService summaryService;
  private final Clock clock;

  public SnippetService(
      final SnippetRepository repository,
      final MongoTemplate mongoTemplate,
      final AggregatedArticleRepository articleRepository,
      final ArticleSummaryService summaryService,
      final Clock clock
  ) {
    this.repository = repository;
    this.mongoTemplate = mongoTemplate;
    this.articleRepository = articleRepository;
    this.summaryService = summaryService;
    this.clock = clock;
  }

  /**
   * Keeps a passage of an article's current summary.
   *
   * @param request the passage and its note and tags
   * @param createdBy the Auth0 subject keeping it
   * @return the stored snippet
   * @throws ResponseStatusException 400 for a bad request, 404 when the article does not
   *     exist, 409 when it has no finished summary, 422 when the summary does not contain the
   *     passage
   */
  public SnippetResponse create(final CreateSnippetRequest request, final String createdBy) {
    String exact = request.exact() == null ? "" : request.exact().trim();
    if (exact.isEmpty()) {
      throw badRequest("Select some text to keep");
    }
    if (exact.length() > SnippetText.MAX_EXACT_LENGTH) {
      throw badRequest("A snippet can be at most %,d characters. Select a shorter passage."
          .formatted(SnippetText.MAX_EXACT_LENGTH));
    }
    String prefix = context(request.prefix());
    String suffix = context(request.suffix());
    String note = note(request.note());
    List<String> tags = normalisedTags(request.tags());
    if (request.contentId() == null || request.contentId().isBlank()) {
      throw badRequest("Say which article the passage came from");
    }

    AggregatedArticle article = articleRepository.findById(request.contentId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such article"));
    ArticleSummary summary = summaryService.findFor(article.id())
        .filter(found -> found.status() == SummaryStatus.READY)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
            "This article has no finished summary to keep a passage from"));
    // Checked against the summary rather than trusted, so a snippet can only ever quote what
    // the summary said.
    if (!SnippetText.summaryContains(summary.body(), exact)) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
          "That passage is not in this article's summary. Reopen the summary and try again.");
    }

    Instant now = clock.instant();
    Snippet saved = repository.save(new Snippet(null, SnippetSourceType.ARTICLE_SUMMARY,
        article.id(), summary.id(), exact, prefix, suffix, note, tags, article.title(),
        article.sourceName(), article.author(), article.originalUrl(),
        article.publishedDate(), createdBy, now, now));
    return SnippetResponse.from(saved, true, article.visible());
  }

  /**
   * Replaces a snippet's note and tags.
   *
   * @param id the snippet
   * @param request the new note and tags
   * @return the updated snippet
   * @throws ResponseStatusException 404 when it does not exist, 400 for a bad note or tag
   */
  public SnippetResponse update(final String id, final UpdateSnippetRequest request) {
    String note = note(request.note());
    List<String> tags = normalisedTags(request.tags());
    Snippet existing = repository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such snippet"));
    Snippet saved = repository.save(existing.withNoteAndTags(note, tags, clock.instant()));
    return withState(List.of(saved)).getFirst();
  }

  /**
   * Deletes a snippet. Deleting one that is already gone is not an error, so a repeated click
   * cannot show a failure for something that worked.
   *
   * @param id the snippet
   */
  public void delete(final String id) {
    repository.deleteById(id);
  }

  /**
   * One article's snippets, oldest first; the drawer orders them by where they sit in the
   * text.
   *
   * @param contentId the aggregated article id
   * @return the snippets
   */
  public List<SnippetResponse> forContent(final String contentId) {
    return withState(repository.findByContentIdOrderByCreatedAtAsc(contentId));
  }

  /**
   * One page of the console's search.
   *
   * @param search the filters
   * @param pageable the page
   * @return the matching snippets
   */
  public Page<SnippetResponse> search(final SnippetSearch search, final Pageable pageable) {
    Criteria criteria = criteria(search);
    Sort sort = SnippetSearch.SORT_ARTICLE.equals(search.sort())
        ? Sort.by(Sort.Order.desc("articlePublishedAt"), Sort.Order.desc(CREATED_AT))
        : Sort.by(Sort.Order.desc(CREATED_AT));
    long total = mongoTemplate.count(Query.query(criteria), Snippet.class);
    List<Snippet> page = mongoTemplate.find(Query.query(criteria).with(sort)
        .skip(pageable.getOffset()).limit(pageable.getPageSize()), Snippet.class);
    return new PageImpl<>(withState(page), pageable, total);
  }

  /**
   * How many snippets each article has, for the badges on the news cards. Only articles with
   * at least one are present: there are a few hundred at most, so one map serves every page.
   *
   * @return article id to snippet count
   */
  public Map<String, Long> countsByContent() {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.group(CONTENT_ID).count().as(COUNT));
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Document row : mongoTemplate.aggregate(aggregation, Snippet.class, Document.class)) {
      counts.put(row.getString("_id"), ((Number) row.get(COUNT)).longValue());
    }
    return counts;
  }

  /**
   * Every tag in use with how many snippets carry it, most used first.
   *
   * @return the tags
   */
  public List<TagCount> tags() {
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.unwind("tags"),
        Aggregation.group("tags").count().as(COUNT),
        Aggregation.sort(Sort.by(Sort.Order.desc(COUNT), Sort.Order.asc("_id"))));
    List<TagCount> tags = new ArrayList<>();
    for (Document row : mongoTemplate.aggregate(aggregation, Snippet.class, Document.class)) {
      tags.add(new TagCount(row.getString("_id"), ((Number) row.get(COUNT)).longValue()));
    }
    return tags;
  }

  /**
   * Every publisher a snippet has come from, for the console's source filter.
   *
   * @return the source names, sorted
   */
  public List<String> sources() {
    return mongoTemplate.findDistinct(new Query(), "sourceName", Snippet.class, String.class)
        .stream()
        .filter(name -> name != null && !name.isBlank())
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }

  private static Criteria criteria(final SnippetSearch search) {
    List<Criteria> clauses = new ArrayList<>();
    List<String> tags = normalisedTags(search.tags());
    if (!tags.isEmpty()) {
      clauses.add(Criteria.where("tags").all(tags));
    }
    List<String> sources = search.sources().stream()
        .filter(source -> source != null && !source.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
    if (!sources.isEmpty()) {
      clauses.add(Criteria.where("sourceName").in(sources));
    }
    if (search.from() != null || search.to() != null) {
      Criteria kept = Criteria.where(CREATED_AT);
      if (search.from() != null) {
        kept = kept.gte(search.from().atStartOfDay().toInstant(ZoneOffset.UTC));
      }
      if (search.to() != null) {
        kept = kept.lt(search.to().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
      }
      clauses.add(kept);
    }
    // Each word is a quoted literal, so typing "C++" matches literally and there is nothing
    // for the regex engine to backtrack over; the same rules as the news page's search box.
    for (String term : ArticleQueryService.terms(search.query())) {
      Pattern pattern = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);
      clauses.add(new Criteria().orOperator(SEARCHED_FIELDS.stream()
          .map(field -> Criteria.where(field).regex(pattern))
          .toArray(Criteria[]::new)));
    }
    if (clauses.isEmpty()) {
      return new Criteria();
    }
    return clauses.size() == 1
        ? clauses.getFirst()
        : new Criteria().andOperator(clauses.toArray(new Criteria[0]));
  }

  /**
   * Adds whether each snippet's article can still be opened, and whether its current summary
   * still contains the quote. One read of the articles and one of the summaries for the whole
   * list, and each summary is rendered to text once however many snippets it has.
   */
  private List<SnippetResponse> withState(final List<Snippet> snippets) {
    if (snippets.isEmpty()) {
      return List.of();
    }
    Set<String> contentIds = snippets.stream()
        .map(Snippet::contentId)
        .collect(Collectors.toSet());
    Set<String> available = new HashSet<>();
    articleRepository.findAllById(contentIds).forEach(article -> {
      if (article.visible()) {
        available.add(article.id());
      }
    });
    Map<String, ArticleSummary> summaries = summaryService.findForAll(contentIds);
    Map<String, String> rendered = new HashMap<>();
    return snippets.stream().map(snippet -> {
      ArticleSummary summary = summaries.get(snippet.contentId());
      boolean inSummary = summary != null
          && summary.status() == SummaryStatus.READY
          && rendered.computeIfAbsent(snippet.contentId(),
              ignored -> SnippetText.renderedText(summary.body()))
              .contains(SnippetText.normalise(snippet.exact()));
      return SnippetResponse.from(snippet, inSummary, available.contains(snippet.contentId()));
    }).toList();
  }

  private static String context(final String value) {
    String context = value == null ? "" : value;
    if (context.length() > SnippetText.MAX_CONTEXT_LENGTH) {
      throw badRequest("The text around a passage can be at most %d characters"
          .formatted(SnippetText.MAX_CONTEXT_LENGTH));
    }
    return context;
  }

  private static String note(final String value) {
    String note = value == null ? "" : value.trim();
    if (note.length() > SnippetText.MAX_NOTE_LENGTH) {
      throw badRequest("A note can be at most %,d characters"
          .formatted(SnippetText.MAX_NOTE_LENGTH));
    }
    return note;
  }

  private static List<String> normalisedTags(final List<String> raw) {
    try {
      return SnippetText.normaliseTags(raw);
    } catch (IllegalArgumentException invalid) {
      throw badRequest(invalid.getMessage());
    }
  }

  private static ResponseStatusException badRequest(final String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
