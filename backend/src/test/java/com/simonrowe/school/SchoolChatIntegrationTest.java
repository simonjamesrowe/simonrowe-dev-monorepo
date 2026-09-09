package com.simonrowe.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import com.simonrowe.school.model.AcademicYear;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The public chat surface, end to end against a real Mongo.
 *
 * <p>Deliberately exercises the parts that do <b>not</b> need a model: the config endpoint, the
 * disabled-feature posture, and the tier filter on stored events. The model call itself is not
 * worth a Testcontainer — its behaviour is pinned by {@code evals/termtime.yaml}, which runs
 * against a real backend, while the thing that must never regress silently is which rows a given
 * caller can reach.
 *
 * <p>Runs with {@code school.enabled} at its default of false, which is also the posture a fresh
 * production deploy has. That the endpoint reports 503 rather than 500 or 404 in that state is
 * itself worth pinning: the frontend renders "not switched on" from it.
 */
class SchoolChatIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private SchoolEventRepository events;

  @Autowired
  private SchoolProperties properties;

  @BeforeEach
  void clear() {
    events.deleteAll();
  }

  @Test
  @DisplayName("the config endpoint is reachable without authentication")
  void configIsPublic() throws Exception {
    mockMvc.perform(get("/api/school/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.yearGroups", org.hamcrest.Matchers.hasSize(7)))
        .andExpect(jsonPath("$.yearGroups[0]").value("Reception"))
        .andExpect(jsonPath("$.authenticated").value(false));
  }

  @Test
  @DisplayName("the feature is off by default, and says so rather than erroring")
  void disabledByDefault() throws Exception {
    assertThat(properties.enabled()).isFalse();

    mockMvc.perform(post("/api/school/chat")
            .contentType("application/json")
            .content("{\"question\":\"When is half term?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.outcome").value("UNAVAILABLE"));
  }

  @Test
  @DisplayName("an anonymous read of stored events returns public rows only")
  void anonymousReadsPublicEventsOnly() {
    final String year = AcademicYear.of(LocalDate.of(2026, 9, 2));
    events.saveAll(List.of(
        new SchoolEvent("public-1", "Inset Day", LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 9, 2), true, SchoolEvent.EventType.INSET, List.of(), year,
            SchoolSourceType.CALENDAR_FEED, "doc-1", Visibility.PUBLIC, null, null, null, null),
        new SchoolEvent("private-1", "Parent meeting about a pupil", LocalDate.of(2026, 9, 3),
            LocalDate.of(2026, 9, 3), true, SchoolEvent.EventType.OTHER, List.of(), year,
            SchoolSourceType.EMAIL, "doc-2", Visibility.RESTRICTED, null, null, null, null)));

    final List<SchoolEvent> anonymous = events.findOverlapping(
        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), List.of(Visibility.PUBLIC));
    final List<SchoolEvent> member = events.findOverlapping(
        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
        List.of(Visibility.PUBLIC, Visibility.RESTRICTED));

    assertThat(anonymous).extracting(SchoolEvent::id).containsExactly("public-1");
    assertThat(member).extracting(SchoolEvent::id)
        .containsExactlyInAnyOrder("public-1", "private-1");
  }

  @Test
  @DisplayName("the overlap query is inclusive at both ends")
  void overlapIsInclusive() {
    // A term that starts on the last day of the window must still be found. An exclusive
    // boundary here would drop exactly the events a "what is on this week" question is about.
    final String year = AcademicYear.of(LocalDate.of(2026, 10, 26));
    events.save(new SchoolEvent("half-term", "Half term", LocalDate.of(2026, 10, 26),
        LocalDate.of(2026, 10, 30), true, SchoolEvent.EventType.HALF_TERM, List.of(), year,
        SchoolSourceType.CALENDAR_FEED, "doc-1", Visibility.PUBLIC, null, null, null, null));

    assertThat(events.findOverlapping(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 5),
        List.of(Visibility.PUBLIC))).hasSize(1);
    assertThat(events.findOverlapping(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 26),
        List.of(Visibility.PUBLIC))).hasSize(1);
    assertThat(events.findOverlapping(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 5),
        List.of(Visibility.PUBLIC))).isEmpty();
  }
}
