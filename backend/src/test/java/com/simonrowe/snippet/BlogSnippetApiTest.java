package com.simonrowe.snippet;

import static com.simonrowe.AdminTestAuth.adminJwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.migration.changeunits.V061CreateSnippets;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/** Snippets of the site's own blog posts, end to end against a real Mongo. */
class BlogSnippetApiTest extends AbstractIntegrationTest {

  private static final String URL = "/api/admin/snippets";
  private static final Instant WRITTEN = Instant.parse("2026-09-30T08:00:00Z");
  private static final String CONTENT = """
      Most teams **measure the wrong thing**.

      ![A chart of review time](/uploads/chart.png)

      | Metric | Why it misleads |
      | --- | --- |
      | Lines of code | It rewards ~~volume~~ churn |

      <iframe src="https://www.youtube.com/embed/abc"></iframe>

      ```mermaid
      graph TD; A-->B
      ```

      Verification is the bottleneck now.
      """;

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private BlogRepository blogRepository;
  @Autowired private AggregatedArticleRepository articleRepository;
  @Autowired private ArticleSummaryRepository summaryRepository;
  @Autowired private SnippetRepository snippetRepository;
  @Autowired private ObjectMapper objectMapper;

  private final List<String> savedPosts = new ArrayList<>();
  private String postId;

  @BeforeEach
  void freshCollections() {
    mongoTemplate.dropCollection(Snippet.COLLECTION);
    V061CreateSnippets.createIndexes(mongoTemplate);
    // Saved with no id, so Mongo assigns an ObjectId, as it does for every real post: the
    // projection that reads posts back must convert the string ids to match.
    postId = savePost("Measuring Engineering", CONTENT, true);
  }

  /** Only what this class wrote: other classes keep their own posts on the shared Mongo. */
  @AfterEach
  void removeWhatThisClassWrote() {
    mongoTemplate.dropCollection(Snippet.COLLECTION);
    blogRepository.deleteAllById(savedPosts);
    articleRepository.deleteAll();
    summaryRepository.deleteAll();
  }

  @Test
  void keepsPassageOfPostAndCitesThePost() throws Exception {
    mockMvc.perform(json(post(URL), blogRequest(postId, "measure the wrong thing")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sourceType").value("BLOG"))
        .andExpect(jsonPath("$.contentId").value(postId))
        .andExpect(jsonPath("$.articleTitle").value("Measuring Engineering"))
        .andExpect(jsonPath("$.sourceName").value("simonrowe.dev"))
        .andExpect(jsonPath("$.author").value("Simon Rowe"))
        .andExpect(jsonPath("$.originalUrl").value("https://simonrowe.dev/blogs/" + postId))
        .andExpect(jsonPath("$.articlePublishedAt").value(WRITTEN.toString()))
        .andExpect(jsonPath("$.inCurrentSummary").value(true))
        .andExpect(jsonPath("$.articleAvailable").value(true));

    Snippet stored = snippetRepository.findAll().getFirst();
    assertThat(stored.sourceType()).isEqualTo(SnippetSourceType.BLOG);
    assertThat(stored.summaryId()).isNull();
  }

  /**
   * The quote is checked against the post as the blog page shows it: across a table's
   * cells, through struck-out text, and over an image, an embed and a diagram, none of which
   * leave any text a reader could select.
   */
  @Test
  void acceptsQuotesShapedByTheBlogPagesRendering() throws Exception {
    mockMvc.perform(json(post(URL), blogRequest(postId,
            "Lines of code\n\nIt rewards volume churn")))
        .andExpect(status().isCreated());
    mockMvc.perform(json(post(URL), blogRequest(postId,
            "measure the wrong thing.\n\nMetric\n\nWhy it misleads")))
        .andExpect(status().isCreated());
    mockMvc.perform(json(post(URL), blogRequest(postId,
            "volume churn\n\nVerification is the bottleneck")))
        .andExpect(status().isCreated());
  }

  @Test
  void refusesPassageThePostNeverSaidOrPostThatDoesNotExist() throws Exception {
    mockMvc.perform(json(post(URL), blogRequest(postId, "graph TD")))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.message").value(
            "That passage is not in this post. Reload the post and try again."));
    mockMvc.perform(json(post(URL), blogRequest(postId, "A chart of review time")))
        .andExpect(status().is(422));
    mockMvc.perform(json(post(URL), blogRequest("000000000000000000000000", "anything")))
        .andExpect(status().isNotFound());
    assertThat(snippetRepository.count()).isZero();
  }

  /** The author can quote a draft; the console then says it cannot be opened on the site. */
  @Test
  void quotesDraftAndSaysWhenThePostChangesOrIsUnpublished() throws Exception {
    String draft = savePost("Draft", "Unfinished thoughts about review.", false);
    mockMvc.perform(json(post(URL), blogRequest(draft, "thoughts about review")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.articleAvailable").value(false));

    keep(postId, "measure the wrong thing");
    keep(postId, "Verification is the bottleneck now.");
    Blog edited = blogRepository.findById(postId).orElseThrow();
    blogRepository.save(new Blog(edited.id(), edited.title(), edited.shortDescription(),
        "Most teams measure the wrong thing.", false, null, WRITTEN, Instant.now(), List.of(),
        List.of(), BlogContentType.ENGINEERING));

    mockMvc.perform(get(URL).param("contentId", postId).param("sourceType", "BLOG")
            .with(owner()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].inCurrentSummary").value(true))
        .andExpect(jsonPath("$[0].articleAvailable").value(false))
        .andExpect(jsonPath("$[1].inCurrentSummary").value(false));
  }

  /** An article and a post with the same id never see each other's snippets or counts. */
  @Test
  void keepsArticleAndPostSnippetsApartEvenOnTheSameId() throws Exception {
    articleRepository.save(new AggregatedArticle(postId, "An article", "TLDR Dev",
        "https://example.com/feed", "https://example.com/a", "Card", "Full", null, WRITTEN,
        WRITTEN, true, null));
    ArticleSummary summary = new ArticleSummary(
        ArticleSummary.idFor("article-summary-v1", postId), postId, Instant.now());
    summary.markReady("Summaries say what articles say.", "test-model", 10, Instant.now());
    summaryRepository.save(summary);
    mockMvc.perform(json(post(URL), new CreateSnippetRequest(SnippetSourceType.ARTICLE_SUMMARY,
            postId, "what articles say", "", "", null, null)))
        .andExpect(status().isCreated());
    keep(postId, "measure the wrong thing");

    // Omitting the type means an article summary, as every client did before posts.
    mockMvc.perform(get(URL).param("contentId", postId).with(owner()))
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].exact").value("what articles say"))
        .andExpect(jsonPath("$[0].inCurrentSummary").value(true));
    mockMvc.perform(get(URL).param("contentId", postId).param("sourceType", "BLOG")
            .with(owner()))
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].exact").value("measure the wrong thing"))
        .andExpect(jsonPath("$[0].inCurrentSummary").value(true));
    mockMvc.perform(get(URL + "/counts").with(owner()))
        .andExpect(jsonPath("$." + postId).value(1));
    mockMvc.perform(get(URL).with(owner()))
        .andExpect(jsonPath("$.content[*].sourceType",
            containsInAnyOrder("ARTICLE_SUMMARY", "BLOG")))
        .andExpect(jsonPath("$.content[*].inCurrentSummary", containsInAnyOrder(true, true)));
    mockMvc.perform(get(URL + "/sources").with(owner()))
        .andExpect(jsonPath("$", containsInAnyOrder("simonrowe.dev", "TLDR Dev")));
  }

  /** A body from a client written before posts could be quoted has no sourceType at all. */
  @Test
  void requestWithNoSourceTypeIsAnArticleSummary() {
    CreateSnippetRequest request = new CreateSnippetRequest(null, "pr", "x", "", "", null, null);
    assertThat(request.sourceType()).isEqualTo(SnippetSourceType.ARTICLE_SUMMARY);
  }

  private void keep(final String blogId, final String exact) throws Exception {
    mockMvc.perform(json(post(URL), blogRequest(blogId, exact)))
        .andExpect(status().isCreated());
  }

  private String savePost(final String title, final String content, final boolean published) {
    Blog saved = blogRepository.save(new Blog(null, title, "About it", content, published,
        null, WRITTEN, WRITTEN, List.of(), List.of(), BlogContentType.ENGINEERING));
    savedPosts.add(saved.id());
    return saved.id();
  }

  private MockHttpServletRequestBuilder json(
      final MockHttpServletRequestBuilder request, final Object body) throws Exception {
    return request.with(owner()).contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body));
  }

  private static CreateSnippetRequest blogRequest(final String blogId, final String exact) {
    return new CreateSnippetRequest(SnippetSourceType.BLOG, blogId, exact, "", "", null, null);
  }

  private static JwtRequestPostProcessor owner() {
    return adminJwt().jwt(token -> token.subject("owner"));
  }
}
