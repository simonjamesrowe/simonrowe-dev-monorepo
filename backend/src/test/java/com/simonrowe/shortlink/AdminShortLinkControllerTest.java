package com.simonrowe.shortlink;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.blog.Blog;
import com.simonrowe.migration.changeunits.V029CreateShortLinksAndBackfill;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class AdminShortLinkControllerTest extends AbstractIntegrationTest {

  private static final SimpleGrantedAuthority ADMIN =
      new SimpleGrantedAuthority("ROLE_DEV_PORTAL_ADMIN");
  private static final SimpleGrantedAuthority VIEWER =
      new SimpleGrantedAuthority("ROLE_VIEWER");

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ShortLinkService shortLinkService;

  @BeforeEach
  @AfterEach
  void reset() {
    mongoTemplate.getCollection("short_links").drop();
    mongoTemplate.getCollection("blogs").drop();
    mongoTemplate.getCollection("aggregated_articles").drop();
    V029CreateShortLinksAndBackfill.createIndexes(mongoTemplate);
  }

  @Test
  void rejectsAnonymous() throws Exception {
    mockMvc.perform(get("/api/admin/short-links"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsAnAuthenticatedCallerWithoutTheAdminRole() throws Exception {
    mockMvc.perform(get("/api/admin/short-links")
            .with(jwt().jwt(j -> j.subject("someone")).authorities(VIEWER)))
        .andExpect(status().isForbidden());
  }

  @Test
  void returnsEveryLinkWithItsJoinedTitleAndStatistics() throws Exception {
    seedBlog("blog-1", "Exactly-once semantics");
    seedArticle("article-1", "Spring AI goes GA");
    String blogSlug = shortLinkService.ensureFor(
        ShortLinkContentType.BLOG, "blog-1", "Exactly-once semantics");
    shortLinkService.ensureFor(
        ShortLinkContentType.ARTICLE, "article-1", "Spring AI goes GA");
    shortLinkService.recordClick(blogSlug);
    shortLinkService.recordClick(blogSlug);

    mockMvc.perform(get("/api/admin/short-links").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(2)))
        // Most-clicked first, so the interesting rows are at the top before any sorting.
        .andExpect(jsonPath("$[0].slug").value(blogSlug))
        .andExpect(jsonPath("$[0].shortUrl")
            .value("https://simonrowe.dev/s/" + blogSlug))
        .andExpect(jsonPath("$[0].contentType").value("BLOG"))
        .andExpect(jsonPath("$[0].contentId").value("blog-1"))
        .andExpect(jsonPath("$[0].title").value("Exactly-once semantics"))
        .andExpect(jsonPath("$[0].clickCount").value(2))
        .andExpect(jsonPath("$[0].lastClickedAt").isNotEmpty())
        .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
        .andExpect(jsonPath("$[1].contentType").value("ARTICLE"))
        .andExpect(jsonPath("$[1].title").value("Spring AI goes GA"))
        .andExpect(jsonPath("$[1].clickCount").value(0))
        .andExpect(jsonPath("$[1].lastClickedAt").doesNotExist());
  }

  @Test
  void keepsAnOrphanedLinkVisibleWithNullTitle() throws Exception {
    // The content is gone but the slug is still in URLs people hold. Dropping the row
    // would hide exactly the link someone might want to know about.
    seedBlog("blog-1", "A post");
    shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");
    mongoTemplate.getCollection("blogs").drop();

    mockMvc.perform(get("/api/admin/short-links").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].contentId").value("blog-1"))
        .andExpect(jsonPath("$[0].title").doesNotExist());
  }

  @Test
  void returnsAnEmptyListWhenNothingHasBeenMinted() throws Exception {
    mockMvc.perform(get("/api/admin/short-links").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(0)));
  }

  @Test
  void blogAdminListCarriesClickCountsPerPost() throws Exception {
    seedBlog("blog-1", "A post");
    seedBlog("blog-2", "Another post");
    String slug = shortLinkService.ensureFor(ShortLinkContentType.BLOG, "blog-1", "A post");
    shortLinkService.recordClick(slug);
    // blog-2 gets no link at all, so its count must be null rather than 0 — "never
    // shared" and "shared but never opened" are different facts.

    mockMvc.perform(get("/api/admin/blogs").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[?(@.id=='blog-1')].clickCount")
            .value(Matchers.contains(1)))
        .andExpect(jsonPath("$.content[?(@.id=='blog-2')].clickCount")
            .value(Matchers.contains(Matchers.nullValue())));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
    return jwt().jwt(j -> j.subject("admin")).authorities(ADMIN);
  }

  private void seedBlog(final String id, final String title) {
    mongoTemplate.save(new Blog(id, title, "description", "content", true, null,
        Instant.now(), Instant.now(), List.of(), List.of(), null), "blogs");
  }

  private void seedArticle(final String id, final String title) {
    mongoTemplate.save(new AggregatedArticle(id, title, "Source",
        "https://example.com", "https://example.com/" + id, "summary", "full", "Author",
        Instant.now(), Instant.now(), true, null), "aggregated_articles");
  }
}
