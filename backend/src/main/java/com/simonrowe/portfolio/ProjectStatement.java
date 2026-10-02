package com.simonrowe.portfolio;

import java.util.List;

/** The large statement band on a project's overview: why it exists, and what it pulls together. */
public record ProjectStatement(String label, String text, List<ProjectHighlight> points) {

  public ProjectStatement {
    points = points == null ? List.of() : List.copyOf(points);
  }
}
