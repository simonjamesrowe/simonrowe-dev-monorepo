package com.simonrowe.agents;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ContentClassification(
    @JsonProperty("type") String type,
    @JsonProperty("summary") String summary,
    @JsonProperty("eventDate") String eventDate,
    @JsonProperty("venue") String venue,
    @JsonProperty("location") String location,
    @JsonProperty("publishedDate") String publishedDate
) {

  public boolean isEvent() {
    return "event".equalsIgnoreCase(type);
  }
}
