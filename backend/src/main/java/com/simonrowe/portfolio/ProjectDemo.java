package com.simonrowe.portfolio;

import java.util.List;

/**
 * A project's demo video. Each URL is a site path or an https URL. Captions are a WebVTT file;
 * without one the player still works, but a narrated video with no captions is silent to
 * anyone who cannot hear it.
 */
public record ProjectDemo(
    String title,
    String summary,
    String videoUrl,
    String captionsUrl,
    String posterUrl,
    List<ProjectChapter> chapters
) {

  public ProjectDemo {
    chapters = chapters == null ? List.of() : List.copyOf(chapters);
  }
}
