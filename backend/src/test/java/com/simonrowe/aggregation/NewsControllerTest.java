package com.simonrowe.aggregation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.json.JsonCompareMode;

class NewsControllerTest extends AbstractIntegrationTest {

  @Autowired
  private AggregatedArticleRepository articleRepository;

  @AfterEach
  void tearDown() {
    articleRepository.deleteAll();
  }

  @Test
  void getLatestNews_returnsArticles() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Spring AI Article", true));

    mockMvc.perform(get("/api/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"))
        .andExpect(jsonPath("$.content[0].title").value("Spring AI Article"))
        .andExpect(jsonPath("$.content[0].sourceName").value("Tech Blog"))
        .andExpect(jsonPath("$.content[0].visible").value(true));
  }

  @Test
  void getLatestNews_returnsEmptyWhenNone() throws Exception {
    mockMvc.perform(get("/api/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void getLatestNews_excludesHiddenArticles() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Visible Article", true));
    articleRepository.save(sampleArticle("a-2", "Hidden Article", false));

    mockMvc.perform(get("/api/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"));
  }

  @Test
  void getLatestNews_filtersBySource() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Tech Blog Article", true));
    articleRepository.save(sampleArticleWithSource(
        "a-2", "Other Source Article", "Other Source", true));

    mockMvc.perform(get("/api/news").param("source", "Tech Blog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"));
  }

  @Test
  void getArticleById_returnsArticle() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Spring AI Article", true));

    mockMvc.perform(get("/api/news/a-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("a-1"))
        .andExpect(jsonPath("$.title").value("Spring AI Article"))
        .andExpect(jsonPath("$.sourceName").value("Tech Blog"))
        .andExpect(jsonPath("$.summary").value("A summary of the article"));
  }

  @Test
  void getArticleById_returnsNotFound() throws Exception {
    mockMvc.perform(get("/api/news/nonexistent"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getArticleById_returnsNotFoundWhenHidden() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Hidden Article", false));

    mockMvc.perform(get("/api/news/a-1"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getSources_returnsDistinctNamesFromDuplicatedSources() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticleWithSource("a-1", "One", "InfoQ", true),
        sampleArticleWithSource("a-2", "Two", "InfoQ", true),
        sampleArticleWithSource("a-3", "Three", "Dan Vega", true),
        sampleArticleWithSource("a-4", "Four", "Dan Vega", true),
        sampleArticleWithSource("a-5", "Five", "Ars Technica", true)
    ));

    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3));
  }

  @Test
  void getSources_returnsNamesByArticleCountThenAlphabetically() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticleWithSource("a-1", "One", "InfoQ", true),
        sampleArticleWithSource("a-2", "Two", "InfoQ", true),
        sampleArticleWithSource("a-3", "Three", "InfoQ", true),
        sampleArticleWithSource("a-4", "Four", "Zebra Blog", true),
        sampleArticleWithSource("a-5", "Five", "Ars Technica", true)
    ));

    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].name").value("InfoQ"))
        .andExpect(jsonPath("$[0].count").value(3))
        // One article each, so the tie breaks alphabetically.
        .andExpect(jsonPath("$[1].name").value("Ars Technica"))
        .andExpect(jsonPath("$[2].name").value("Zebra Blog"));
  }

  @Test
  void getSources_countsOnlyVisibleArticlesPerSource() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticleWithSource("a-1", "One", "Spring Blog", true),
        sampleArticleWithSource("a-2", "Two", "Spring Blog", true),
        sampleArticleWithSource("a-3", "Three", "Spring Blog", false)
    ));

    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Spring Blog"))
        .andExpect(jsonPath("$[0].count").value(2));
  }

  @Test
  void getSources_excludesSourcesOnlyHiddenArticlesHave() throws Exception {
    articleRepository.saveAll(List.of(
        sampleArticleWithSource("a-1", "Visible", "InfoQ", true),
        sampleArticleWithSource("a-2", "Hidden", "Hidden Source", false)
    ));

    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("InfoQ"))
        .andExpect(jsonPath("$[0].count").value(1));
  }

  @Test
  void getSources_returnsEmptyArrayWhenNoArticles() throws Exception {
    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]", JsonCompareMode.STRICT));
  }

  @Test
  void getSources_isNotShadowedByTheByIdMapping() throws Exception {
    articleRepository.save(sampleArticle("sources", "Article Whose Id Is sources", true));

    mockMvc.perform(get("/api/news/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].name").value("Tech Blog"));
  }

  private AggregatedArticle sampleArticle(
      final String id, final String title, final boolean visible) {
    return sampleArticleWithSource(id, title, "Tech Blog", visible);
  }

  private AggregatedArticle sampleArticleWithSource(
      final String id, final String title, final String sourceName, final boolean visible) {
    return new AggregatedArticle(
        id,
        title,
        sourceName,
        "https://techblog.example.com",
        "https://techblog.example.com/articles/" + id,
        "A summary of the article",
        "Full article content here.",
        "Test Author",
        Instant.parse("2026-01-15T10:00:00Z"),
        Instant.parse("2026-01-15T11:00:00Z"),
        visible,
        null);
  }
}
