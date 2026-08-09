package com.simonrowe.factory.feedback.agent;

import java.nio.file.Path;
import java.util.List;

/** One repo the distiller may edit: an open checkout plus its editing rules. */
public record DistillTarget(
    String owner, String repository, Path workspace, List<String> allowedPaths,
    String description) {

  public DistillTarget {
    allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
  }
}
