package com.simonrowe.aggregation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EventsControllerTest extends AbstractIntegrationTest {

  @Autowired
  private AggregatedEventRepository eventRepository;

  @AfterEach
  void tearDown() {
    eventRepository.deleteAll();
  }

  @Test
  void getUpcomingEvents_returnsEvents() throws Exception {
    eventRepository.save(upcomingEvent("e-1", "AI Conference 2026", true));

    mockMvc.perform(get("/api/events").param("upcoming", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("e-1"))
        .andExpect(jsonPath("$.content[0].title").value("AI Conference 2026"))
        .andExpect(jsonPath("$.content[0].sourceName").value("Luma"));
  }

  @Test
  void getUpcomingEvents_returnsEmptyWhenNone() throws Exception {
    mockMvc.perform(get("/api/events").param("upcoming", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void getUpcomingEvents_excludesHiddenEvents() throws Exception {
    eventRepository.save(upcomingEvent("e-1", "Visible Event", true));
    eventRepository.save(upcomingEvent("e-2", "Hidden Event", false));

    mockMvc.perform(get("/api/events").param("upcoming", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("e-1"));
  }

  @Test
  void getUpcomingEvents_excludesPastEvents() throws Exception {
    eventRepository.save(pastEvent("e-1", "Past Event", true));
    eventRepository.save(upcomingEvent("e-2", "Future Event", true));

    mockMvc.perform(get("/api/events").param("upcoming", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("e-2"));
  }

  @Test
  void getEvents_defaultReturnUpcomingEvents() throws Exception {
    eventRepository.save(upcomingEvent("e-1", "Future Event", true));

    mockMvc.perform(get("/api/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value("e-1"));
  }

  @Test
  void getEventById_returnsEvent() throws Exception {
    eventRepository.save(upcomingEvent("e-1", "AI Conference 2026", true));

    mockMvc.perform(get("/api/events/e-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("e-1"))
        .andExpect(jsonPath("$.title").value("AI Conference 2026"))
        .andExpect(jsonPath("$.sourceName").value("Luma"))
        .andExpect(jsonPath("$.venue").value("London ExCeL"))
        .andExpect(jsonPath("$.location").value("London, UK"));
  }

  @Test
  void getEventById_returnsNotFound() throws Exception {
    mockMvc.perform(get("/api/events/nonexistent"))
        .andExpect(status().isNotFound());
  }

  private AggregatedEvent upcomingEvent(
      final String id, final String title, final boolean visible) {
    Instant eventDate = Instant.now().plus(30, ChronoUnit.DAYS);
    return new AggregatedEvent(
        id,
        title,
        "Luma",
        "https://lu.ma/events/" + id,
        "A summary of the event.",
        "Full event description here.",
        eventDate,
        eventDate.plus(8, ChronoUnit.HOURS),
        "London ExCeL",
        "London, UK",
        Instant.now(),
        visible);
  }

  private AggregatedEvent pastEvent(
      final String id, final String title, final boolean visible) {
    Instant eventDate = Instant.now().minus(30, ChronoUnit.DAYS);
    return new AggregatedEvent(
        id,
        title,
        "Luma",
        "https://lu.ma/events/" + id,
        "A summary of a past event.",
        "Full event description here.",
        eventDate,
        eventDate.plus(8, ChronoUnit.HOURS),
        "London ExCeL",
        "London, UK",
        Instant.now().minus(31, ChronoUnit.DAYS),
        visible);
  }
}
