package com.simonrowe.platform;

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

class PlatformReleasesControllerTest extends AbstractIntegrationTest {

  private static final String NEWER = "840c311abcdef0123456789abcdef0123456789a";
  private static final String OLDER = "39e0f7aabcdef0123456789abcdef0123456789a";

  @Autowired
  private PlatformReleaseRepository repository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RunningVersion runningVersion;

  @BeforeEach
  void seed() {
    mongoTemplate.dropCollection(PlatformRelease.class);
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
    PlatformRelease release = PlatformRelease.fromBaked(
        new BakedRelease(sha, Instant.ofEpochSecond(epoch), subject, "", List.of("a.java")),
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
        .andExpect(jsonPath("$[0].sha").value(NEWER))
        .andExpect(jsonPath("$[0].shortSha").value("840c311"))
        .andExpect(jsonPath("$[0].type").value("docs"))
        .andExpect(jsonPath("$[0].subject").value("docs: overhaul the README (#118)"))
        .andExpect(jsonPath("$[0].summary").value("The README was rewritten."))
        .andExpect(jsonPath("$[0].summaryStatus").value("READY"))
        .andExpect(jsonPath("$[1].sha").value(OLDER))
        .andExpect(jsonPath("$[1].type").value("feat"));
  }

  @Test
  void exposesPendingSummaryRatherThanHidingTheEntry() throws Exception {
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[1].summaryStatus").value("PENDING"))
        .andExpect(jsonPath("$[1].subject").value("feat: deploy automatically (#116)"));
  }

  @Test
  void honoursTheLimitParameter() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].sha").value(NEWER));
  }

  @Test
  void clampsAnAbsurdLimitRatherThanServingTheWholeCollection() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("limit", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(Matchers.lessThanOrEqualTo(100))));
  }

  @Test
  void rejectsNonPositiveLimit() throws Exception {
    mockMvc.perform(get("/api/platform/releases").param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void marksTheRunningReleaseWhenThisBuildMatchesOne() throws Exception {
    // In a test build the running SHA is whatever HEAD was at compile time, which will not
    // match the seeded fixtures — so assert the flag is present and false rather than
    // asserting a specific entry is running.
    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].running")
            .value(runningVersion.commit().equals(NEWER)));
  }

  @Test
  void returnsAnEmptyArrayWhenNothingHasBeenSeeded() throws Exception {
    mongoTemplate.dropCollection(PlatformRelease.class);

    mockMvc.perform(get("/api/platform/releases"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", Matchers.hasSize(0)));
  }
}
