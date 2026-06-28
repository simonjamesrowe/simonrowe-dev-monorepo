package com.simonrowe.chat;

import java.util.List;

public record CodeWidgetPayload(List<Example> examples) {

  public record Example(
      String id,
      String title,
      String description,
      String language,
      String code,
      List<String> skills
  ) {
  }
}
