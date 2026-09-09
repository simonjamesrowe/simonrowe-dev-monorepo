package com.simonrowe.school.ingest;

import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.model.YearGroups;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the school's calendar.
 *
 * <p>The school's CMS exposes a JSON endpoint that backs its own calendar widget, and an iCal
 * feed. This uses the JSON one: it carries a stable numeric event id, per-event deep links, an
 * attachment flag, and — the reason it matters here — which sub-calendars each event belongs to,
 * which is how an event gets attributed to a year group at all. The iCal feed flattens that away.
 *
 * <p>This is the highest-precedence source. The school's own term-dates web page is a year out of
 * date while this feed is current, so anything that reads dates must prefer this.
 */
@Component
public class CalendarFeedClient {

  private static final Logger LOG = LoggerFactory.getLogger(CalendarFeedClient.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final SchoolProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public CalendarFeedClient(final SchoolProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  }

  /**
   * Fetches every event in a date range across all sub-calendars.
   *
   * @param from range start, inclusive
   * @param to range end, inclusive
   * @return the events, or an empty list if the feed could not be read
   */
  public List<CalendarFeedEvent> fetchRange(final LocalDate from, final LocalDate to) {
    final String calids = YearGroups.allCalendarIds().stream()
        .map(String::valueOf)
        .collect(Collectors.joining(","));
    final String url = "%s/api.asp?pid=3&viewid=1&calid=%s&bgedit=false&start=%s&end=%s"
        .formatted(properties.calendarBaseUrl(), calids, from, to);

    try {
      final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", "SimonRoweBot/1.0 (+https://simonrowe.dev)")
          .header("Accept", "application/json")
          .timeout(TIMEOUT)
          .GET()
          .build();
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warn("Calendar feed returned {} for {}", response.statusCode(), url);
        return List.of();
      }
      return parse(response.body());
    } catch (IOException e) {
      LOG.warn("Could not read the calendar feed: {}", e.getMessage());
      return List.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }

  List<CalendarFeedEvent> parse(final String body) {
    final JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (RuntimeException e) {
      LOG.warn("Calendar feed was not valid JSON: {}", e.getMessage());
      return List.of();
    }
    if (!root.isArray()) {
      LOG.warn("Calendar feed was not a JSON array");
      return List.of();
    }

    final List<CalendarFeedEvent> events = new ArrayList<>();
    for (JsonNode node : root) {
      toEvent(node).ifPresent(events::add);
    }
    return events;
  }

  private Optional<CalendarFeedEvent> toEvent(final JsonNode node) {
    final String title = text(node, "title");
    final String start = text(node, "start");
    if (title.isBlank() || start.isBlank()) {
      return Optional.empty();
    }
    final LocalDate startDate;
    try {
      startDate = LocalDate.parse(start);
    } catch (RuntimeException e) {
      LOG.debug("Skipping calendar row with unparseable start '{}'", start);
      return Optional.empty();
    }

    // The feed omits "end" for single-day events rather than repeating the start.
    final String end = text(node, "end");
    LocalDate endDate = startDate;
    if (!end.isBlank()) {
      try {
        endDate = LocalDate.parse(end);
      } catch (RuntimeException e) {
        endDate = startDate;
      }
    }

    final List<Integer> calendarIds = new ArrayList<>();
    final JsonNode cals = node.get("cals");
    if (cals != null && cals.isArray()) {
      for (JsonNode cal : cals) {
        final JsonNode id = cal.get("id");
        if (id != null && id.isNumber()) {
          calendarIds.add(id.asInt());
        }
      }
    }

    return Optional.of(new CalendarFeedEvent(
        text(node, "id"),
        title.trim(),
        startDate,
        endDate,
        node.path("allDay").asBoolean(true),
        text(node, "time"),
        text(node, "desc"),
        text(node, "url"),
        List.copyOf(calendarIds)));
  }

  private static String text(final JsonNode node, final String field) {
    final JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asString();
  }
}
