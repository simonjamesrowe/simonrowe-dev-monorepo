package com.simonrowe.favourites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.aggregation.AggregatedEvent;
import com.simonrowe.aggregation.AggregatedEventRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

class FavouritesControllerTest extends AbstractIntegrationTest {

  private static final String USER_A = "auth0|user-a";
  private static final String USER_B = "auth0|user-b";

  @Autowired
  private FavouriteRepository favouriteRepository;

  @Autowired
  private AggregatedArticleRepository articleRepository;

  @Autowired
  private AggregatedEventRepository eventRepository;

  @AfterEach
  void tearDown() {
    favouriteRepository.deleteAll();
    articleRepository.deleteAll();
    eventRepository.deleteAll();
  }

  @Test
  void addFavourite_thenIdsAndListingContainIt() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Spring AI Article", true));

    mockMvc.perform(put("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/favourites/news/ids"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0]").value("a-1"));

    mockMvc.perform(get("/api/favourites/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"))
        .andExpect(jsonPath("$.content[0].title").value("Spring AI Article"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.number").value(0))
        .andExpect(jsonPath("$.size").value(20));
  }

  @Test
  void addFavourite_eventType() throws Exception {
    eventRepository.save(sampleEvent("e-1", "Spring Meetup"));

    mockMvc.perform(put("/api/favourites/events/e-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/favourites/events/ids"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("e-1"));

    mockMvc.perform(get("/api/favourites/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value("e-1"))
        .andExpect(jsonPath("$.content[0].venue").value("Test Venue"));
  }

  @Test
  void removeFavourite_disappearsFromIdsAndListing() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Article", true));
    mockMvc.perform(put("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());

    mockMvc.perform(delete("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/favourites/news/ids"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mockMvc.perform(get("/api/favourites/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void removeFavourite_isIdempotentWhenNotPresent() throws Exception {
    mockMvc.perform(delete("/api/favourites/news/never-favourited").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());
  }

  @Test
  void addFavourite_isIdempotent() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Article", true));

    mockMvc.perform(put("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());
    mockMvc.perform(put("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());

    assertThat(favouriteRepository.count()).isEqualTo(1);
  }

  @Test
  void reads_arePublic_writesRequireAuthentication() throws Exception {
    mockMvc.perform(put("/api/favourites/news/a-1")).andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/favourites/news/a-1")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/favourites/news/ids")).andExpect(status().isOk());
    mockMvc.perform(get("/api/favourites/news")).andExpect(status().isOk());
  }

  @Test
  void favourites_areGlobal_notScopedPerUser() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Shared Article", true));
    mockMvc.perform(put("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());

    // A different user sees the same favourite...
    mockMvc.perform(get("/api/favourites/news/ids").with(userJwt(USER_B)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0]").value("a-1"));

    // ...and can remove it for everyone.
    mockMvc.perform(delete("/api/favourites/news/a-1").with(userJwt(USER_B)))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/favourites/news/ids"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void addFavourite_returnsNotFoundForUnknownContent() throws Exception {
    mockMvc.perform(put("/api/favourites/news/no-such-article").with(userJwt(USER_A)))
        .andExpect(status().isNotFound());
    mockMvc.perform(put("/api/favourites/events/no-such-event").with(userJwt(USER_A)))
        .andExpect(status().isNotFound());
    assertThat(favouriteRepository.count()).isZero();
  }

  @Test
  void unknownType_returnsBadRequest() throws Exception {
    mockMvc.perform(put("/api/favourites/podcasts/x").with(userJwt(USER_A)))
        .andExpect(status().isBadRequest());
    mockMvc.perform(delete("/api/favourites/podcasts/x").with(userJwt(USER_A)))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/favourites/podcasts/ids"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/favourites/podcasts"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listFavourites_ordersByMostRecentlyFavouritedFirst() throws Exception {
    articleRepository.save(sampleArticle("a-1", "First Saved", true));
    articleRepository.save(sampleArticle("a-2", "Second Saved", true));
    favouriteRepository.insert(new Favourite(
        null, FavouriteType.NEWS, "a-1", Instant.parse("2026-07-01T10:00:00Z")));
    favouriteRepository.insert(new Favourite(
        null, FavouriteType.NEWS, "a-2", Instant.parse("2026-07-02T10:00:00Z")));

    mockMvc.perform(get("/api/favourites/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.content[0].id").value("a-2"))
        .andExpect(jsonPath("$.content[1].id").value("a-1"));
  }

  @Test
  void listFavourites_includesHiddenContent() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Hidden Article", false));
    mockMvc.perform(put("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/favourites/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"))
        .andExpect(jsonPath("$.content[0].visible").value(false));
  }

  @Test
  void listFavourites_skipsDeletedContent() throws Exception {
    articleRepository.save(sampleArticle("a-1", "Kept", true));
    articleRepository.save(sampleArticle("a-2", "Deleted Later", true));
    mockMvc.perform(put("/api/favourites/news/a-1").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());
    mockMvc.perform(put("/api/favourites/news/a-2").with(userJwt(USER_A)))
        .andExpect(status().isNoContent());
    articleRepository.deleteById("a-2");

    mockMvc.perform(get("/api/favourites/news"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("a-1"));
  }

  private JwtRequestPostProcessor userJwt(final String subject) {
    return jwt().jwt(jwt -> jwt.subject(subject));
  }

  private AggregatedArticle sampleArticle(
      final String id, final String title, final boolean visible) {
    return new AggregatedArticle(
        id,
        title,
        "Tech Blog",
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

  private AggregatedEvent sampleEvent(final String id, final String title) {
    return new AggregatedEvent(
        id,
        title,
        "Meetup Source",
        "https://events.example.com/" + id,
        "An event summary",
        "Full event description",
        Instant.parse("2026-09-01T18:00:00Z"),
        Instant.parse("2026-09-01T21:00:00Z"),
        "Test Venue",
        "London",
        Instant.parse("2026-07-01T00:00:00Z"),
        true);
  }
}
