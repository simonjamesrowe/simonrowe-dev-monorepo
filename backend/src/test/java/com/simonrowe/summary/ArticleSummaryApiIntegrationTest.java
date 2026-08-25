package com.simonrowe.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.embabel.agent.api.common.PromptRunner;
import com.embabel.chat.AssistantMessage;
import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.ArticleSourceTextProvider;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The wired API: real security filter chain, real validation, real MongoDB. The model and
 * the scraper are the only things mocked — both have their own dedicated tests, and neither
 * should be reached over the network from a build.
 */
class ArticleSummaryApiIntegrationTest extends AbstractIntegrationTest {

  private static final String ARTICLE_ID = "integration-article-1";
  private static final String USABLE_SOURCE = "Substantive article body. ".repeat(30);

  // Ai is already a @MockitoBean on AbstractIntegrationTest, inherited as `ai`.
  // The source-text provider is mocked so the build never reaches out over the network to
  // scrape a real article; it has its own dedicated test.
  @MockitoBean private ArticleSourceTextProvider sourceTextProvider;

  @Autowired private AggregatedArticleRepository articleRepository;
  @Autowired private ArticleSummaryRepository summaryRepository;

  private PromptRunner promptRunner;

  @BeforeEach
  void setUp() {
    promptRunner = mock(PromptRunner.class);
    AssistantMessage message = mock(AssistantMessage.class);
    lenient().when(ai.withLlm(anyString())).thenReturn(promptRunner);
    lenient().when(promptRunner.respond(anyList())).thenReturn(message);
    lenient().when(message.getContent())
        .thenReturn("Paragraph one.\n\nParagraph two.\n\nParagraph three.");
    lenient().when(sourceTextProvider.sourceTextFor(org.mockito.ArgumentMatchers.any()))
        .thenReturn(USABLE_SOURCE);
    articleRepository.save(new AggregatedArticle(
        ARTICLE_ID, "Spring Boot 4 Released", "InfoQ", "https://infoq.com",
        "https://infoq.com/integration-" + ARTICLE_ID, "Stored blurb.",
        "Stored content.", "Jane Doe", Instant.now(), Instant.now(), true, null));
  }

  @AfterEach
  void clean() {
    summaryRepository.deleteAll();
    articleRepository.deleteById(ARTICLE_ID);
  }

  @Test
  void anonymousPostIsRejectedButAnonymousReadsAreNot() throws Exception {
    mockMvc.perform(post("/api/news/" + ARTICLE_ID + "/summary"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(get("/api/news/" + ARTICLE_ID + "/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("NOT_REQUESTED"));

    verify(promptRunner, never()).respond(anyList());
  }

  @Test
  void authenticatedPostGeneratesAndTheSummaryThenReadsPubliclyWithNoFurtherSpend()
      throws Exception {
    mockMvc.perform(post("/api/news/" + ARTICLE_ID + "/summary")
            .with(jwt().jwt(j -> j.subject("reader"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"))
        .andExpect(jsonPath("$.body").value(
            "Paragraph one.\n\nParagraph two.\n\nParagraph three."));

    mockMvc.perform(get("/api/news/" + ARTICLE_ID + "/summary"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"));

    mockMvc.perform(get("/api/news/summaries/ids"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value(ARTICLE_ID));

    // A second POST must reuse rather than re-spend.
    mockMvc.perform(post("/api/news/" + ARTICLE_ID + "/summary")
            .with(jwt().jwt(j -> j.subject("reader"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("READY"));

    verify(promptRunner, org.mockito.Mockito.times(1)).respond(anyList());
    assertThat(summaryRepository.count()).isEqualTo(1);
  }

  /**
   * The bound exists so a long-poll cannot pin a request thread indefinitely. It is
   * enforced by {@code @Validated} method validation, which only runs inside a real
   * application context — hence the assertion lives here rather than in the standalone
   * controller test.
   */
  @Test
  void waitSecondsAboveTwentyFiveIsRejected() throws Exception {
    mockMvc.perform(get("/api/news/" + ARTICLE_ID + "/summary")
            .param("waitSeconds", "26"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void waitSecondsAtTheUpperBoundIsAccepted() throws Exception {
    // Terminal (NOT_REQUESTED) with no afterVersion, so it returns at once rather than
    // actually holding for 25 seconds.
    mockMvc.perform(get("/api/news/" + ARTICLE_ID + "/summary")
            .param("waitSeconds", "25"))
        .andExpect(status().isOk());
  }

  @Test
  void negativeWaitSecondsIsRejected() throws Exception {
    mockMvc.perform(get("/api/news/" + ARTICLE_ID + "/summary")
            .param("waitSeconds", "-1"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void thinSourceTextFailsNonRetryablyAndIsNotRetriedOnLaterPosts() throws Exception {
    when(sourceTextProvider.sourceTextFor(org.mockito.ArgumentMatchers.any()))
        .thenReturn("Only a feed snippet.");

    mockMvc.perform(post("/api/news/" + ARTICLE_ID + "/summary")
            .with(jwt().jwt(j -> j.subject("reader"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("FAILED"))
        .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_SOURCE_TEXT"))
        .andExpect(jsonPath("$.retryable").value(false));

    mockMvc.perform(post("/api/news/" + ARTICLE_ID + "/summary")
            .with(jwt().jwt(j -> j.subject("reader"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failureCode").value("INSUFFICIENT_SOURCE_TEXT"));

    verify(promptRunner, never()).respond(anyList());
    // A failed summary must not make the card claim one is available to read.
    mockMvc.perform(get("/api/news/summaries/ids"))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void summariesIdsIsPublicAndListsOnlyReadyOnes() throws Exception {
    mockMvc.perform(get("/api/news/summaries/ids"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    mockMvc.perform(post("/api/news/" + ARTICLE_ID + "/summary")
            .with(jwt().jwt(j -> j.subject("reader"))))
        .andExpect(status().isOk());

    // No token on this read: it is the logged-out visitor's view.
    mockMvc.perform(get("/api/news/summaries/ids"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0]").value(ARTICLE_ID));
  }

  @Test
  void anInvisibleArticleIsNotSummarisable() throws Exception {
    articleRepository.save(new AggregatedArticle(
        ARTICLE_ID, "Hidden", "InfoQ", "https://infoq.com",
        "https://infoq.com/integration-" + ARTICLE_ID, "Blurb.", "Content.",
        null, Instant.now(), Instant.now(), false, null));

    mockMvc.perform(post("/api/news/" + ARTICLE_ID + "/summary")
            .with(jwt().jwt(j -> j.subject("reader"))))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/news/" + ARTICLE_ID + "/summary"))
        .andExpect(status().isNotFound());
  }
}
