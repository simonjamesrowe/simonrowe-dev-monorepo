package com.simonrowe.search;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SearchController.class)
class SearchControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SearchService searchService;

  @Test
  void siteSearchReturnsGroupedResults() throws Exception {
    GroupedSearchResponse response = new GroupedSearchResponse(
        List.of(new SearchResult(
            "Java Blog", "/images/java.jpg", "/blogs/java")),
        List.of(),
        List.of(new SearchResult(
            "Java", "/images/skills/java.png", "/skills")));
    when(searchService.siteSearch(anyString())).thenReturn(response);

    mockMvc.perform(get("/api/search").param("q", "java"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.blogs[0].name").value("Java Blog"))
        .andExpect(jsonPath("$.skills[0].name").value("Java"));
  }

  @Test
  void blogSearchReturnsBlogResults() throws Exception {
    List<BlogSearchResult> results = List.of(
        new BlogSearchResult(
            "Spring Boot Guide", "A guide", "/images/spring.jpg",
            Instant.parse("2025-11-15T00:00:00Z"), "/blogs/spring-boot"));
    when(searchService.blogSearch(anyString())).thenReturn(results);

    mockMvc.perform(get("/api/search/blogs").param("q", "spring"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("Spring Boot Guide"));
  }

  @Test
  void siteSearchMissingQueryParamReturns400() throws Exception {
    mockMvc.perform(get("/api/search"))
        .andExpect(status().isBadRequest());
  }
}
