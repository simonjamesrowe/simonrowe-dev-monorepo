package com.simonrowe.platform;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PlatformReleasesControllerTest extends AbstractIntegrationTest {

  private static final String NEWER = "840c311abcdef0123456789abcdef0123456789a";
  private static final String OLDER = "39e0f7aabcdef0123456789abcdef0123456789a";

  /** Matches neither seeded fixture, so it is the deterministic "nothing is running" case. */
  private static final String NON_MATCHING_SHA = "0000000000000000000000000000000000000a";

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  /**
   * Mocked (rather than the real, build-info-backed instance) so {@code running} can be
   * exercised deterministically in both directions: the real bean reports whatever SHA this
   * test binary was built from, which never matches a hardcoded fixture, so the {@code true}
   * branch of {@code ReleaseResponse.from} would otherwise never run.
   */
  @MockitoBean
  private RunningVersion runningVersion;

  @BeforeEach
  void seed() {
    mongoTemplate.dropCollection(PlatformRelease.class);
    when(runningVersion.commit()).thenReturn(NON_MATCHING_SHA);
    store(NEWER, 1756200000L, "docs: overhaul the README (#118)", ReleaseSummaryStatus.READY,
        "The README was rewritten.");
    store(OLDER, 1756100000L, "feat: deploy automatically (#116)", ReleaseSummaryStatus.PENDING,
        null);
  }

  private void store(
      final String sha,
      final long epoch,
      final String subject,
      final ReleaseSummaryStatus status,
      final String summary) {
    PlatformRelease release = PlatformRelease.fromCommit(
        new MainCommit(sha, Instant.ofEpochSecond(epoch), subject, "", List.of("a.java")),
        ReleaseSource.PUBLISHED_HISTORY,
        Instant.ofEpochSecond(epoch));
    release.setSummaryStatus(status);
    release.setSummary(summary);
    repository.save(release);
  }

  @Test
  void isPublicAndNeedsNoAuthentication() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk());
  }

  @Test
  void returnsReleasesNewestFirst() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sha").value(NEWER))
        .andExpect(jsonPath("$.items[0].shortSha").value("840c311"))
        .andExpect(jsonPath("$.items[0].type").value("docs"))
        .andExpect(jsonPath("$.items[0].subject").value("docs: overhaul the README (#118)"))
        .andExpect(jsonPath("$.items[0].summary").value("The README was rewritten."))
        .andExpect(jsonPath("$.items[0].summaryStatus").value("READY"))
        .andExpect(jsonPath("$.items[1].sha").value(OLDER))
        .andExpect(jsonPath("$.items[1].type").value("feat"));
  }

  @Test
  void describesThePageAndTheWholeHistory() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(ReleaseQueryService.DEFAULT_PAGE_SIZE))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.totalReleases").value(2))
        .andExpect(jsonPath("$.typeCounts.docs").value(1))
        .andExpect(jsonPath("$.typeCounts.feat").value(1));
  }

  @Test
  void exposesPendingSummaryRatherThanHidingTheEntry() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[1].summaryStatus").value("PENDING"))
        .andExpect(jsonPath("$.items[1].subject").value("feat: deploy automatically (#116)"));
  }

  @Test
  void pagesThroughTheWholeHistory() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("size", "1").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", Matchers.hasSize(1)))
        .andExpect(jsonPath("$.items[0].sha").value(OLDER))
        .andExpect(jsonPath("$.totalItems").value(2))
        .andExpect(jsonPath("$.totalPages").value(2));
  }

  @Test
  void returnsAnEmptyPageBeyondTheEnd() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("page", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", Matchers.hasSize(0)))
        .andExpect(jsonPath("$.totalItems").value(2));
  }

  @Test
  void clampsAnAbsurdPageSizeRatherThanServingTheWholeCollection() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("size", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(ReleaseQueryService.MAX_PAGE_SIZE));
  }

  @Test
  void rejectsNonPositivePageSizeAndNegativePage() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("size", "0"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/platform/releases").param("page", "-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void filtersByType() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("type", "feat"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", Matchers.hasSize(1)))
        .andExpect(jsonPath("$.items[0].sha").value(OLDER))
        .andExpect(jsonPath("$.totalItems").value(1))
        // Counts label the pills and do not narrow with the filter.
        .andExpect(jsonPath("$.totalReleases").value(2))
        .andExpect(jsonPath("$.typeCounts.docs").value(1));
  }

  @Test
  void searchesTheSubjectTheReleaseNoteAndTheSha() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("q", "DEPLOY"))
        .andExpect(jsonPath("$.items[*].sha").value(Matchers.contains(OLDER)));
    mockMvc.perform(get("/api/platform/releases").param("q", "rewritten"))
        .andExpect(jsonPath("$.items[*].sha").value(Matchers.contains(NEWER)));
    mockMvc.perform(get("/api/platform/releases").param("q", "39e0f7a"))
        .andExpect(jsonPath("$.items[*].sha").value(Matchers.contains(OLDER)));
  }

  @Test
  void requiresEveryTermButLetsEachMatchAnyField() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("q", "readme rewritten"))
        .andExpect(jsonPath("$.items[*].sha").value(Matchers.contains(NEWER)));
    mockMvc.perform(get("/api/platform/releases").param("q", "readme deploy"))
        .andExpect(jsonPath("$.items", Matchers.hasSize(0)));
  }

  @Test
  void treatsRegexCharactersInTheSearchLiterally() throws Exception {
    // Each term is quoted, so a visitor typing a pattern cannot make the engine backtrack.
    mockMvc.perform(get("/api/platform/releases").param("q", "(a+)+$ .*"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", Matchers.hasSize(0)));
  }

  @Test
  void marksTheMatchingReleaseAsRunningAndLeavesTheOtherNotRunning() throws Exception {
    when(runningVersion.commit()).thenReturn(NEWER);

    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sha").value(NEWER))
        .andExpect(jsonPath("$.items[0].running").value(true))
        .andExpect(jsonPath("$.items[1].sha").value(OLDER))
        .andExpect(jsonPath("$.items[1].running").value(false));
  }

  @Test
  void marksEveryReleaseAsNotRunningWhenNoStoredReleaseMatches() throws Exception {
    when(runningVersion.commit()).thenReturn(NON_MATCHING_SHA);

    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].running").value(false))
        .andExpect(jsonPath("$.items[1].running").value(false));
  }

  @Test
  void returnsAnEmptyPageWhenNothingHasBeenRecorded() throws Exception {
    mongoTemplate.dropCollection(PlatformRelease.class);

    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", Matchers.hasSize(0)))
        .andExpect(jsonPath("$.totalReleases").value(0));
  }
}
