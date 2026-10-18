package com.simonrowe.playlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.aggregation.AggregatedArticle;
import com.simonrowe.aggregation.AggregatedArticleRepository;
import com.simonrowe.blog.Blog;
import com.simonrowe.blog.BlogContentType;
import com.simonrowe.blog.BlogRepository;
import com.simonrowe.favourites.FavouriteRepository;
import com.simonrowe.narration.Narration;
import com.simonrowe.narration.NarrationContentType;
import com.simonrowe.narration.NarrationStorage;
import com.simonrowe.summary.ArticleSummary;
import com.simonrowe.summary.ArticleSummaryFailure;
import com.simonrowe.summary.ArticleSummaryRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * The playlist API against a real Mongo: what joins it, the order it plays in, what a
 * signed-out visitor may do, and the joins the page reads.
 */
class PlaylistApiIntegrationTest extends AbstractIntegrationTest {

  private static final String SUMMARY_FORMAT_VERSION = "article-summary-v1";
  private static final Instant NOW = Instant.parse("2026-10-12T09:00:00Z");

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private AggregatedArticleRepository articleRepository;
  @Autowired private BlogRepository blogRepository;
  @Autowired private ArticleSummaryRepository summaryRepository;
  @Autowired private FavouriteRepository favouriteRepository;
  @Autowired private PlaylistService playlistService;

  @BeforeEach
  @AfterEach
  void clean() {
    mongoTemplate.remove(new Query(), PlaylistItem.class);
    mongoTemplate.remove(new Query(), PlaylistSettings.class);
    mongoTemplate.remove(new Query(), Narration.class);
    articleRepository.deleteAll();
    blogRepository.deleteAll();
    summaryRepository.deleteAll();
    favouriteRepository.deleteAll();
  }

  // ------------------------------------------------------------------ reading

  @Test
  void anEmptyPlaylistIsAnEmptyListForAnybody() throws Exception {
    mockMvc.perform(get("/api/playlist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.autoVoice").value(false));
  }

  @Test
  void audioWithReadAlongTimingsCarriesTheirUrl() throws Exception {
    article("a-2", "Agent observability", true);
    readySummary("a-2", "Traces follow the GenAI conventions.");
    String id = NarrationContentType.ARTICLE_SUMMARY + "-a-2";
    Narration narration = new Narration(id, NarrationContentType.ARTICLE_SUMMARY, "a-2", 3500,
        "voice", "en-GB", "MP3", "narrations/" + id + ".mp3", NOW);
    narration.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/" + id + "/audio-abc.mp3", 4096, "checksum", 172,
        "/uploads/narrations/" + id + "/timings-abc.json"), NOW);
    mongoTemplate.save(narration);
    add("articles", "a-2");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].audio.audioUrl")
            .value("/uploads/narrations/" + id + "/audio-abc.mp3"))
        .andExpect(jsonPath("$.items[0].audio.timingsUrl")
            .value("/uploads/narrations/" + id + "/timings-abc.json"));
  }

  @Test
  void anArticleIsListedWithItsSummaryAudioAndHeart() throws Exception {
    article("a-1", "Grieving the Loss of Details", true);
    readySummary("a-1", "Details used to be where craft lived.");
    readyAudio(NarrationContentType.ARTICLE_SUMMARY, "a-1", 255);
    add("articles", "a-1");
    favourite("a-1");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].contentType").value("ARTICLE_SUMMARY"))
        .andExpect(jsonPath("$.items[0].contentId").value("a-1"))
        .andExpect(jsonPath("$.items[0].status").value("QUEUED"))
        .andExpect(jsonPath("$.items[0].addedVia").value("ADDED"))
        .andExpect(jsonPath("$.items[0].title").value("Grieving the Loss of Details"))
        .andExpect(jsonPath("$.items[0].href").value("https://example.com/a-1"))
        .andExpect(jsonPath("$.items[0].external").value(true))
        .andExpect(jsonPath("$.items[0].sourceName").value("TLDR Dev"))
        .andExpect(jsonPath("$.items[0].favourite").value(true))
        .andExpect(jsonPath("$.items[0].summaryState").value("READY"))
        .andExpect(jsonPath("$.items[0].audio.state").value("READY"))
        .andExpect(jsonPath("$.items[0].audio.audioUrl")
            .value("/uploads/narrations/ARTICLE_SUMMARY-a-1/narration.mp3"))
        .andExpect(jsonPath("$.items[0].audio.durationSeconds").value(255))
        // Audio made before read-along has no timings, and the field is left out.
        .andExpect(jsonPath("$.items[0].audio.timingsUrl").doesNotExist())
        // Only items still waiting for audio carry a guess at its length.
        .andExpect(jsonPath("$.items[0].estimatedSeconds").doesNotExist());
  }

  @Test
  void anItemWaitingForAudioCarriesAnEstimateOfItsLength() throws Exception {
    article("a-1", "Title", true);
    readySummary("a-1", "x".repeat(1395));

    add("articles", "a-1");

    // (5 + 1395) characters at 14 a second.
    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].audio.state").value("NOT_REQUESTED"))
        .andExpect(jsonPath("$.items[0].audio.audioUrl").doesNotExist())
        .andExpect(jsonPath("$.items[0].estimatedSeconds").value(100))
        .andExpect(jsonPath("$.items[0].favourite").value(false));
  }

  @Test
  void postIsReadInFullAndCannotBeHearted() throws Exception {
    blog("b-1", "I'm Rubbish at Recording Demos");

    add("blogs", "b-1");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].contentType").value("BLOG"))
        .andExpect(jsonPath("$.items[0].href").value("/blogs/b-1"))
        .andExpect(jsonPath("$.items[0].external").value(false))
        .andExpect(jsonPath("$.items[0].sourceName").value("Simon Rowe"))
        .andExpect(jsonPath("$.items[0].favourite").doesNotExist())
        .andExpect(jsonPath("$.items[0].summaryState").doesNotExist());
  }

  @Test
  void contentHiddenOrDeletedSinceItWasAddedIsLeftOut() throws Exception {
    article("a-1", "Kept", true);
    article("a-2", "Hidden later", true);
    article("a-3", "Deleted later", true);
    add("articles", "a-1");
    add("articles", "a-2");
    add("articles", "a-3");
    article("a-2", "Hidden later", false);
    articleRepository.deleteById("a-3");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].contentId").value("a-1"));
  }

  // ------------------------------------------------------------------ signed out

  @Test
  void signedOutVisitorCanReadButChangeNothing() throws Exception {
    article("a-1", "Title", true);

    mockMvc.perform(put("/api/playlist/items/articles/a-1"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/playlist/items/articles/a-1"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(put("/api/playlist/items/articles/a-1/progress")
            .contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":12}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/playlist/items/articles/a-1/listened"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(post("/api/playlist/narrations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(audioBody("ARTICLE_SUMMARY", "a-1")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(put("/api/playlist/settings")
            .contentType(MediaType.APPLICATION_JSON).content("{\"autoVoice\":true}"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(delete("/api/playlist/listened"))
        .andExpect(status().isUnauthorized());

    assertThat(mongoTemplate.findAll(PlaylistItem.class)).isEmpty();
  }

  // ------------------------------------------------------------------ adding

  @Test
  void addingMissingContentIsNotFoundAndUnknownTypeIsBadRequest() throws Exception {
    mockMvc.perform(put("/api/playlist/items/articles/missing").with(user()))
        .andExpect(status().isNotFound());
    mockMvc.perform(put("/api/playlist/items/podcasts/x").with(user()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addingSomethingAlreadyQueuedLeavesItWhereItIs() throws Exception {
    article("a-1", "First", true);
    article("a-2", "Second", true);
    add("articles", "a-1");
    add("articles", "a-2");

    add("articles", "a-1");

    // Same dates, so the newest addition comes first; re-adding a-1 did not make it new.
    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].contentId").value("a-2"))
        .andExpect(jsonPath("$.items[1].contentId").value("a-1"));
  }

  @Test
  void heartingAnArticlePutsItOnThePlaylist() throws Exception {
    article("a-1", "Hearted", true);

    mockMvc.perform(put("/api/favourites/news/a-1").with(user()))
        .andExpect(status().isNoContent());

    PlaylistItem item = item("ARTICLE_SUMMARY:a-1");
    assertThat(item.addedVia()).isEqualTo(PlaylistSource.FAVOURITED);
    assertThat(item.status()).isEqualTo(PlaylistItemStatus.QUEUED);
  }

  @Test
  void recordingNeverReplacesAnItemAlreadyThere() {
    article("a-1", "Title", true);
    playlistService.record(PlaylistContentType.ARTICLE_SUMMARY, "a-1", PlaylistSource.SUMMARISED);
    playlistService.markListened(PlaylistContentType.ARTICLE_SUMMARY, "a-1");

    playlistService.record(PlaylistContentType.ARTICLE_SUMMARY, "a-1", PlaylistSource.FAVOURITED);

    PlaylistItem item = item("ARTICLE_SUMMARY:a-1");
    assertThat(item.addedVia()).isEqualTo(PlaylistSource.SUMMARISED);
    assertThat(item.status()).isEqualTo(PlaylistItemStatus.LISTENED);
  }

  @Test
  void recordingSomethingThatDoesNotExistAddsNothingAndDoesNotThrow() {
    playlistService.record(PlaylistContentType.BLOG, "missing", PlaylistSource.LISTENED);

    assertThat(mongoTemplate.findAll(PlaylistItem.class)).isEmpty();
  }

  // ------------------------------------------------------------------ playing

  @Test
  void queuePlaysNewestContentFirstWhateverOrderItWasAdded() throws Exception {
    article("a-old", "Last month", true, NOW.minus(Duration.ofDays(30)));
    article("a-new", "This morning", true, NOW);
    article("a-mid", "Last week", true, NOW.minus(Duration.ofDays(7)));
    add("articles", "a-new");
    add("articles", "a-old");
    add("articles", "a-mid");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].contentId").value("a-new"))
        .andExpect(jsonPath("$.items[1].contentId").value("a-mid"))
        .andExpect(jsonPath("$.items[2].contentId").value("a-old"))
        .andExpect(jsonPath("$.items[0].publishedAt").value("2026-10-12T09:00:00Z"));
  }

  @Test
  void playNextPutsAnItemFirstAndTheLatestChoiceAheadOfEarlierOnes() throws Exception {
    article("a-new", "Newest", true, NOW);
    article("a-old", "Oldest", true, NOW.minus(Duration.ofDays(30)));
    article("a-mid", "Middle", true, NOW.minus(Duration.ofDays(7)));
    add("articles", "a-new");
    add("articles", "a-old");
    add("articles", "a-mid");

    playNext("a-old");
    Thread.sleep(5);
    playNext("a-mid");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].contentId").value("a-mid"))
        .andExpect(jsonPath("$.items[1].contentId").value("a-old"))
        .andExpect(jsonPath("$.items[1].pinnedAt").isNotEmpty())
        .andExpect(jsonPath("$.items[2].contentId").value("a-new"))
        .andExpect(jsonPath("$.items[2].pinnedAt").doesNotExist());
  }

  @Test
  void hearingPinnedItemUnpinsIt() throws Exception {
    article("a-1", "Pinned", true);
    add("articles", "a-1");
    playNext("a-1");

    listened("a-1");
    add("articles", "a-1");

    assertThat(item("ARTICLE_SUMMARY:a-1").pinnedAt()).isNull();
  }

  @Test
  void playNextIsSignedInOnly() throws Exception {
    article("a-1", "Title", true);
    add("articles", "a-1");

    mockMvc.perform(post("/api/playlist/items/articles/a-1/play-next"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void progressIsKeptUntilTheItemIsHeardToTheEnd() throws Exception {
    article("a-1", "Title", true);
    add("articles", "a-1");

    mockMvc.perform(put("/api/playlist/items/articles/a-1/progress").with(user())
            .contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":102.5}"))
        .andExpect(status().isNoContent());
    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].progressSeconds").value(102.5));

    mockMvc.perform(post("/api/playlist/items/articles/a-1/listened").with(user()))
        .andExpect(status().isNoContent());
    // A save that arrives after "listened" must not put the item back to half-heard.
    mockMvc.perform(put("/api/playlist/items/articles/a-1/progress").with(user())
            .contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":200}"))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].status").value("LISTENED"))
        .andExpect(jsonPath("$.items[0].progressSeconds").value(0.0))
        .andExpect(jsonPath("$.items[0].listenedAt").isNotEmpty());
  }

  @Test
  void negativeOrAbsurdPositionIsRejected() throws Exception {
    article("a-1", "Title", true);
    add("articles", "a-1");

    mockMvc.perform(put("/api/playlist/items/articles/a-1/progress").with(user())
            .contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":-1}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(put("/api/playlist/items/articles/a-1/progress").with(user())
            .contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":90000}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void heardItemsComeAfterTheQueueMostRecentFirst() throws Exception {
    article("a-1", "One", true);
    article("a-2", "Two", true);
    article("a-3", "Three", true);
    add("articles", "a-1");
    add("articles", "a-2");
    add("articles", "a-3");

    listened("a-1");
    Thread.sleep(5);
    listened("a-2");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].contentId").value("a-3"))
        .andExpect(jsonPath("$.items[1].contentId").value("a-2"))
        .andExpect(jsonPath("$.items[2].contentId").value("a-1"));
  }

  @Test
  void playAgainQueuesHeardItemAtTheEndFromTheStart() throws Exception {
    article("a-1", "One", true);
    article("a-2", "Two", true);
    add("articles", "a-1");
    add("articles", "a-2");
    listened("a-1");

    add("articles", "a-1");

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].contentId").value("a-2"))
        .andExpect(jsonPath("$.items[1].contentId").value("a-1"))
        .andExpect(jsonPath("$.items[1].status").value("QUEUED"))
        .andExpect(jsonPath("$.items[1].listenedAt").doesNotExist());
  }

  @Test
  void clearingHeardItemsLeavesTheQueue() throws Exception {
    article("a-1", "One", true);
    article("a-2", "Two", true);
    add("articles", "a-1");
    add("articles", "a-2");
    listened("a-1");

    mockMvc.perform(delete("/api/playlist/listened").with(user()))
        .andExpect(status().isNoContent());

    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].contentId").value("a-2"));
  }

  @Test
  void removingTakesAnItemOff() throws Exception {
    article("a-1", "One", true);
    add("articles", "a-1");

    mockMvc.perform(delete("/api/playlist/items/articles/a-1").with(user()))
        .andExpect(status().isNoContent());

    assertThat(mongoTemplate.findAll(PlaylistItem.class)).isEmpty();
  }

  // ------------------------------------------------------------------ making audio

  @Test
  void makingAudioReportsEachItemAndMovesNothing() throws Exception {
    article("a-1", "No summary yet", true);
    article("a-2", "Cannot be summarised", true);
    failedSummary("a-2");
    article("a-3", "Already there", true);
    blog("b-1", "A post");
    add("articles", "a-1");
    add("articles", "a-2");
    add("articles", "a-3");
    add("blogs", "b-1");

    mockMvc.perform(post("/api/playlist/narrations").with(user())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"items":[
                  {"contentType":"BLOG","contentId":"b-1"},
                  {"contentType":"ARTICLE_SUMMARY","contentId":"a-1"},
                  {"contentType":"ARTICLE_SUMMARY","contentId":"a-1"},
                  {"contentType":"ARTICLE_SUMMARY","contentId":"a-2"},
                  {"contentType":"ARTICLE_SUMMARY","contentId":"not-there"}]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()").value(4))
        // Text-to-speech is switched off in tests, which is what UNAVAILABLE reports.
        .andExpect(jsonPath("$.results[0].outcome").value("UNAVAILABLE"))
        .andExpect(jsonPath("$.results[1].outcome").value("SUMMARISING"))
        .andExpect(jsonPath("$.results[2].outcome").value("INELIGIBLE"))
        .andExpect(jsonPath("$.results[3].outcome").value("NOT_IN_PLAYLIST"));

    assertThat(item("ARTICLE_SUMMARY:a-1").voiceRequested()).isTrue();
    assertThat(item("ARTICLE_SUMMARY:a-2").voiceRequested()).isFalse();
    // Same dates throughout, so the newest addition comes first, exactly as before the request.
    mockMvc.perform(get("/api/playlist"))
        .andExpect(jsonPath("$.items[0].contentId").value("b-1"))
        .andExpect(jsonPath("$.items[1].contentId").value("a-3"))
        .andExpect(jsonPath("$.items[2].contentId").value("a-2"))
        .andExpect(jsonPath("$.items[3].contentId").value("a-1"));
  }

  @Test
  void makingAudioForMoreThanTwentyItemsAtOnceIsRefused() throws Exception {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < 21; i++) {
      items.append(i == 0 ? "" : ",")
          .append("{\"contentType\":\"BLOG\",\"contentId\":\"b-").append(i).append("\"}");
    }

    mockMvc.perform(post("/api/playlist/narrations").with(user())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"items\":[" + items + "]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void automaticAudioIsSettingAnyoneSignedInCanChange() throws Exception {
    mockMvc.perform(put("/api/playlist/settings").with(user())
            .contentType(MediaType.APPLICATION_JSON).content("{\"autoVoice\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.autoVoice").value(true));

    mockMvc.perform(get("/api/playlist")).andExpect(jsonPath("$.autoVoice").value(true));
  }

  // ------------------------------------------------------------------ helpers

  private void add(final String type, final String id) throws Exception {
    mockMvc.perform(put("/api/playlist/items/" + type + "/" + id).with(user()))
        .andExpect(status().isNoContent());
    // Ranks are epoch millis; keep consecutive additions distinct.
    Thread.sleep(2);
  }

  private void listened(final String articleId) throws Exception {
    mockMvc.perform(post("/api/playlist/items/articles/" + articleId + "/listened").with(user()))
        .andExpect(status().isNoContent());
  }

  private PlaylistItem item(final String id) {
    return mongoTemplate.findById(id, PlaylistItem.class);
  }

  private static String audioBody(final String type, final String id) {
    return "{\"items\":[{\"contentType\":\"" + type + "\",\"contentId\":\"" + id + "\"}]}";
  }

  private static JwtRequestPostProcessor user() {
    return jwt().jwt(token -> token.subject("auth0|listener"));
  }

  private void article(final String id, final String title, final boolean visible) {
    article(id, title, visible, NOW);
  }

  private void article(
      final String id,
      final String title,
      final boolean visible,
      final Instant published
  ) {
    articleRepository.save(new AggregatedArticle(id, title, "TLDR Dev",
        "https://example.com", "https://example.com/" + id, "Feed snippet", "Body",
        "Author", published, NOW, visible, "/uploads/" + id + "_large.png"));
  }

  private void playNext(final String articleId) throws Exception {
    mockMvc.perform(post("/api/playlist/items/articles/" + articleId + "/play-next").with(user()))
        .andExpect(status().isNoContent());
  }

  private void blog(final String id, final String title) {
    blogRepository.save(new Blog(id, title, "Short", "Post body.", true, null, NOW, NOW,
        null, null, BlogContentType.ENGINEERING));
  }

  private void readySummary(final String articleId, final String body) {
    ArticleSummary summary = new ArticleSummary(
        ArticleSummary.idFor(SUMMARY_FORMAT_VERSION, articleId), articleId, NOW);
    summary.markReady(body, "test-model", 4000, NOW);
    summaryRepository.save(summary);
  }

  private void failedSummary(final String articleId) {
    ArticleSummary summary = new ArticleSummary(
        ArticleSummary.idFor(SUMMARY_FORMAT_VERSION, articleId), articleId, NOW);
    summary.markFailed(ArticleSummaryFailure.INSUFFICIENT_SOURCE_TEXT, false, NOW);
    summaryRepository.save(summary);
  }

  private void readyAudio(
      final NarrationContentType contentType,
      final String contentId,
      final long durationSeconds
  ) {
    String id = contentType + "-" + contentId;
    Narration narration = new Narration(id, contentType, contentId, 3500, "voice", "en-GB",
        "MP3", "narrations/" + id + ".mp3", NOW);
    narration.markReady(new NarrationStorage.StoredNarration(
        "/uploads/narrations/" + id + "/narration.mp3", 4096, "checksum", durationSeconds), NOW);
    mongoTemplate.save(narration);
  }

  private void favourite(final String articleId) throws Exception {
    mockMvc.perform(put("/api/favourites/news/" + articleId).with(user()))
        .andExpect(status().isNoContent());
  }
}
