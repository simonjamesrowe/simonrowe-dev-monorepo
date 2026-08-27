package com.simonrowe.narration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Delegation, the wire format and the content-type binding.
 *
 * <p>Standalone MockMvc against a mocked service, the shape {@code SummaryNarrationControllerTest}
 * uses: the controller is pure delegation, and the newest-per-content-id behaviour it delegates to
 * is asserted against a real Mongo in {@link NarrationReadyAggregationTest}.
 */
class NarrationReadyControllerTest {

  private NarrationService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = mock(NarrationService.class);
    mockMvc = MockMvcBuilders
        .standaloneSetup(new NarrationReadyController(service))
        .build();
  }

  @Test
  void listsReadyBlogNarrationsWithTheFieldNamesTheCardsRead() throws Exception {
    when(service.readyNarrations(NarrationContentType.BLOG)).thenReturn(List.of(
        new ReadyNarration("blog-1", "/uploads/narrations/aaa/narration.mp3", 734L),
        new ReadyNarration("blog-2", "/uploads/narrations/bbb/narration.mp3", 412L)));

    mockMvc.perform(get("/api/narrations/ready").param("contentType", "BLOG"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].contentId").value("blog-1"))
        .andExpect(jsonPath("$[0].audioUrl").value("/uploads/narrations/aaa/narration.mp3"))
        .andExpect(jsonPath("$[0].durationSeconds").value(734))
        .andExpect(jsonPath("$[1].contentId").value("blog-2"));

    verify(service).readyNarrations(NarrationContentType.BLOG);
  }

  /**
   * The {@code contentId} carried here is the aggregated <em>article</em> id, not the summary id,
   * which is what lets the news listing key straight off ids it already holds.
   */
  @Test
  void listsReadyArticleSummaryNarrationsKeyedByArticleId() throws Exception {
    when(service.readyNarrations(NarrationContentType.ARTICLE_SUMMARY)).thenReturn(List.of(
        new ReadyNarration("article-9", "/uploads/narrations/ccc/narration.mp3", 180L)));

    mockMvc.perform(get("/api/narrations/ready").param("contentType", "ARTICLE_SUMMARY"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].contentId").value("article-9"))
        .andExpect(jsonPath("$[0].durationSeconds").value(180));

    verify(service).readyNarrations(NarrationContentType.ARTICLE_SUMMARY);
  }

  /**
   * "Nothing is narrated yet" is a normal state for a listing page, not an error — every card
   * just reads "Listen". An empty array, never a 404.
   */
  @Test
  void emptyResultIsAnEmptyArrayRatherThanNotFound() throws Exception {
    when(service.readyNarrations(NarrationContentType.BLOG)).thenReturn(List.of());

    mockMvc.perform(get("/api/narrations/ready").param("contentType", "BLOG"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void missingContentTypeIsRejected() throws Exception {
    mockMvc.perform(get("/api/narrations/ready"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unrecognisedContentTypeIsRejected() throws Exception {
    mockMvc.perform(get("/api/narrations/ready").param("contentType", "ARTICLE_FULL"))
        .andExpect(status().isBadRequest());
  }
}
