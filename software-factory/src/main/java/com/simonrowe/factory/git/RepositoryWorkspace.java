package com.simonrowe.factory.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A disposable checkout of a repository. Closing it deletes the whole temporary tree, so callers
 * should use it in a try-with-resources block.
 */
public final class RepositoryWorkspace implements AutoCloseable {

  private final Path root;
  private final Path repository;
  private final String defaultBranch;

  RepositoryWorkspace(final Path root, final Path repository, final String defaultBranch) {
    this.root = root;
    this.repository = repository;
    this.defaultBranch = defaultBranch;
  }

  /** The checkout directory itself, the working directory for git and the agent. */
  public Path repository() {
    return repository;
  }

  /** The cloned repository's default branch name, as reported by git. */
  public String defaultBranch() {
    return defaultBranch;
  }

  @Override
  public void close() {
    deleteTree(root);
  }

  static void deleteTree(final Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(RepositoryWorkspace::deleteQuietly);
    } catch (IOException ignored) {
      // A failed cleanup must not hide the activity's useful failure.
    }
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup under a unique temporary root.
    }
  }
}
