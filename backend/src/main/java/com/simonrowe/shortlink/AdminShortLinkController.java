package com.simonrowe.shortlink;

import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin shared-links table.
 *
 * <p>Under {@code /api/admin/**}, which {@code SecurityConfig} already gates on the admin
 * role — no new matcher, and no way to reach this without one.
 *
 * <p>Unpaged deliberately: there is one row per piece of content, so a few hundred, and
 * the table is read-only. {@code AdminBlogController} pages because blogs are edited from
 * their list; nothing here is. Sorting happens in the browser for the same reason.
 */
@RestController
@RequestMapping("/api/admin/short-links")
public class AdminShortLinkController {

  private final ShortLinkRepository shortLinkRepository;
  private final ShortLinkService shortLinkService;
  private final BlogRepository blogRepository;
  private final AggregatedArticleRepository articleRepository;
  private final AggregatedEventRepository eventRepository;

  public AdminShortLinkController(
      final ShortLinkRepository shortLinkRepository,
      final ShortLinkService shortLinkService,
      final BlogRepository blogRepository,
      final AggregatedArticleRepository articleRepository,
      final AggregatedEventRepository eventRepository
  ) {
    this.shortLinkRepository = shortLinkRepository;
    this.shortLinkService = shortLinkService;
    this.blogRepository = blogRepository;
    this.articleRepository = articleRepository;
    this.eventRepository = eventRepository;
  }

  /**
   * Every share link with its click statistics, most-clicked first.
   *
   * <p>Titles are joined with three batched reads — one per source collection — rather
   * than one per row.
   *
   * @return the rows, empty when nothing has been minted
   */
  @GetMapping
  public List<AdminShortLinkResponse> list() {
    List<ShortLink> links = shortLinkRepository.findAll();
    Map<ShortLinkContentType, Map<String, String>> titles = titlesFor(links);

    List<AdminShortLinkResponse> rows = new ArrayList<>(links.size());
    for (ShortLink link : links) {
      rows.add(new AdminShortLinkResponse(
          link.slug(),
          shortLinkService.urlOf(link.slug()),
          link.contentType(),
          link.contentId(),
          titles.getOrDefault(link.contentType(), Map.of()).get(link.contentId()),
          link.clickCount(),
          link.lastClickedAt(),
          link.createdAt()));
    }

    // A sensible default the browser can then re-sort: the interesting question is which
    // links people actually opened.
    rows.sort(Comparator.comparingLong(AdminShortLinkResponse::clickCount).reversed()
        .thenComparing(AdminShortLinkResponse::slug));
    return rows;
  }

  /**
   * Loads the title for every linked item, three batched reads rather than one per row.
   *
   * <p>An id with no entry is content that has been deleted; the row keeps its null title
   * rather than being dropped, so an orphaned link stays visible to whoever might want to
   * know about it.
   */
  private Map<ShortLinkContentType, Map<String, String>> titlesFor(final List<ShortLink> links) {
    Map<ShortLinkContentType, List<String>> idsByType = new HashMap<>();
    for (ShortLink link : links) {
      idsByType.computeIfAbsent(link.contentType(), type -> new ArrayList<>())
          .add(link.contentId());
    }

    Map<ShortLinkContentType, Map<String, String>> titles = new HashMap<>();
    titles.put(ShortLinkContentType.BLOG, index(
        blogRepository.findAllById(idsByType.getOrDefault(ShortLinkContentType.BLOG, List.of())),
        Blog::id, Blog::title));
    titles.put(ShortLinkContentType.ARTICLE, index(
        articleRepository.findAllById(
            idsByType.getOrDefault(ShortLinkContentType.ARTICLE, List.of())),
        AggregatedArticle::id, AggregatedArticle::title));
    titles.put(ShortLinkContentType.EVENT, index(
        eventRepository.findAllById(
            idsByType.getOrDefault(ShortLinkContentType.EVENT, List.of())),
        AggregatedEvent::id, AggregatedEvent::title));
    return titles;
  }

  private static <T> Map<String, String> index(
      final Iterable<T> items,
      final Function<T, String> id,
      final Function<T, String> title
  ) {
    Map<String, String> indexed = new HashMap<>();
    for (T item : items) {
      String value = title.apply(item);
      if (value != null) {
        indexed.put(id.apply(item), value);
      }
    }
    return indexed;
  }
}
