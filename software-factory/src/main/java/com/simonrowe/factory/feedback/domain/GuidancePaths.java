package com.simonrowe.factory.feedback.domain;

import com.simonrowe.factory.git.PathGlobs;
import java.util.List;
import java.util.Optional;

/**
 * The files a guidance pull request may touch, per target repository.
 *
 * <p>One list, read by two checks that must never disagree. The distiller validates its own
 * changes against it before pushing, and the reviewer re-checks a guidance pull request's files
 * against it before arming auto-merge. If the reviewer kept a copy, a widened distill allowlist
 * would push files the reviewer then refused, or, worse, a narrowed one would leave the reviewer
 * still merging paths the distiller is no longer meant to write. Matching is {@link PathGlobs}'s.
 */
public final class GuidancePaths {

  /** What the distiller may change in the org-wide agent guidance repository. */
  public static final List<String> AGENT_SETUP =
      List.of(
          "components/instructions/global.md",
          "components/instructions/monorepo-additions.md",
          "components/skills/**");

  /** What the distiller may change in the repository a lesson came from. */
  public static final List<String> SOURCE_REPOSITORY = List.of("CLAUDE.md");

  private GuidancePaths() {
  }

  /**
   * The allowlist for a target repository.
   *
   * @param owner the repository owner
   * @param repository the repository name
   * @param agentSetupRepo {@code factory.feedback.agent-setup-repo}, as {@code owner/name}
   * @return the globs a guidance pull request against that repository may touch
   */
  public static List<String> forRepository(
      final String owner, final String repository, final String agentSetupRepo) {
    return (owner + "/" + repository).equalsIgnoreCase(agentSetupRepo)
        ? AGENT_SETUP
        : SOURCE_REPOSITORY;
  }

  /**
   * The first path no glob matches, if any.
   *
   * @param paths repository-relative paths
   * @param globs the allowlist
   * @return the first path outside the allowlist, or empty when every path is inside it
   */
  public static Optional<String> firstOutside(final List<String> paths, final List<String> globs) {
    return PathGlobs.outside(paths, globs).stream().findFirst();
  }
}
