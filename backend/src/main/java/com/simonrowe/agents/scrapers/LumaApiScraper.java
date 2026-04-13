package com.simonrowe.agents.scrapers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LumaApiScraper {

  private static final Logger log = LoggerFactory.getLogger(LumaApiScraper.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String LUMA_API_URL =
      "https://api.lu.ma/calendar/get-items?calendar_api_id=%s&period=future";
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .build();

  public List<ScrapedContent> scrape(String calendarId) {
    List<ScrapedContent> results = new ArrayList<>();
    try {
      String url = String.format(LUMA_API_URL, calendarId);
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(15))
          .GET()
          .build();
      HttpResponse<String> response = HTTP_CLIENT.send(request,
          HttpResponse.BodyHandlers.ofString());

      JsonNode root = MAPPER.readTree(response.body());
      JsonNode entries = root.path("entries");
      if (!entries.isArray()) {
        log.warn("No entries array in Luma response for calendar {}", calendarId);
        return results;
      }

      for (JsonNode entry : entries) {
        JsonNode event = entry.path("event");
        if (event.isMissingNode()) {
          continue;
        }

        String title = event.path("name").asText("");
        if (title.isBlank()) {
          continue;
        }

        String eventUrl = event.path("url").asText("");
        if (!eventUrl.startsWith("http")) {
          eventUrl = "https://lu.ma/" + eventUrl;
        }

        Instant startAt = null;
        String startStr = event.path("start_at").asText("");
        if (!startStr.isEmpty()) {
          try {
            startAt = Instant.parse(startStr);
          } catch (Exception ignored) {
            // skip unparseable dates
          }
        }

        String description = event.path("description").asText("");

        String venue = null;
        String location = null;
        JsonNode geoInfo = event.path("geo_address_info");
        if (!geoInfo.isMissingNode()) {
          String city = geoInfo.path("city").asText("");
          String country = geoInfo.path("country").asText("");
          location = city.isEmpty() ? country
              : country.isEmpty() ? city : city + ", " + country;
        }
        JsonNode addressJson = event.path("geo_address_json");
        if (!addressJson.isMissingNode()) {
          venue = addressJson.path("description").asText(null);
        }

        String imageUrl = event.path("cover_url").asText(null);

        results.add(new ScrapedContent(
            title, eventUrl, description, startAt, null, imageUrl, true, venue, location));
      }
      log.info("Scraped {} events from Luma calendar: {}", results.size(), calendarId);
    } catch (Exception e) {
      log.error("Failed to scrape Luma calendar: {}", calendarId, e);
    }
    return results;
  }
}
