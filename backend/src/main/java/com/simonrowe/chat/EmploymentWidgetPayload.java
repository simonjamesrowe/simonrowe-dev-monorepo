package com.simonrowe.chat;

import java.util.List;

public record EmploymentWidgetPayload(List<Job> jobs) {

  public record Job(
      String company,
      String title,
      String start,
      String end,
      String summary,
      List<String> skills
  ) {
  }
}
