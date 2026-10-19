package com.simonrowe.factory.git;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * Matches repository-relative paths against an allowlist of globs.
 *
 * <p>Glob semantics are {@link FileSystem#getPathMatcher}'s, where {@code **} crosses directory
 * boundaries and {@code *} does not. That is a different dialect from {@code MergeDisposition}'s
 * bash {@code case} globs on purpose: allowlists here name specific files, and a {@code *} that
 * silently crossed {@code /} would widen them.
 */
public final class PathGlobs {

  private PathGlobs() {
  }

  /**
   * Every path no glob matches, in the order given.
   *
   * @param paths repository-relative paths
   * @param globs the allowlist
   * @return the paths outside the allowlist; empty when every path is inside it
   */
  public static List<String> outside(final List<String> paths, final List<String> globs) {
    FileSystem fileSystem = FileSystems.getDefault();
    List<PathMatcher> matchers =
        globs.stream().map(glob -> fileSystem.getPathMatcher("glob:" + glob)).toList();
    return paths.stream()
        .filter(path -> matchers.stream().noneMatch(matcher -> matcher.matches(Path.of(path))))
        .toList();
  }
}
