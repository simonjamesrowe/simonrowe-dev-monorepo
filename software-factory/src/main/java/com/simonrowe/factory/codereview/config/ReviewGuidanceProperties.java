package com.simonrowe.factory.codereview.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which written standards the reviewer is given, and from where.
 *
 * <p>The reviewer runs Claude Code with {@code --safe-mode}, which loads no {@code CLAUDE.md}: a
 * pull request could otherwise rewrite the instructions it is reviewed against. The cost was that
 * the reviewer had never read the standards at all, including every lesson the feedback loop
 * distils into {@code CLAUDE.md} and agent-setup's {@code global.md}. These settings name the
 * sections worth giving it instead, read from places a pull request cannot change: the source
 * repository at its base commit, and the organisation's instructions at their default branch.
 *
 * <p>A record of its own rather than a component of {@link CodeReviewProperties}, which about
 * twenty tests construct positionally.
 *
 * @param enabled whether any guidance is given; off reviews exactly as before
 * @param repositoryFile the file read from the pull request's base commit
 * @param repositorySections its level-two headings to include. Not the whole file: this
 *     repository's {@code CLAUDE.md} is mostly a change log and runs to hundreds of kilobytes
 * @param organisationRepository {@code owner/name} of the organisation's instructions, used only
 *     for pull requests whose owner matches
 * @param organisationFile the file read there, at the default branch
 * @param organisationSections its level-two headings to include
 * @param maxChars the most characters taken from each file, cut at a line boundary
 */
@ConfigurationProperties("factory.codereview.guidance")
public record ReviewGuidanceProperties(
    boolean enabled,
    String repositoryFile,
    List<String> repositorySections,
    String organisationRepository,
    String organisationFile,
    List<String> organisationSections,
    int maxChars) {

  public ReviewGuidanceProperties {
    repositorySections = repositorySections == null ? List.of() : List.copyOf(repositorySections);
    organisationSections =
        organisationSections == null ? List.of() : List.copyOf(organisationSections);
    maxChars = maxChars > 0 ? maxChars : 12_000;
  }

  /** No guidance at all, as tests that do not exercise it want. */
  public static ReviewGuidanceProperties disabled() {
    return new ReviewGuidanceProperties(false, null, List.of(), null, null, List.of(), 0);
  }
}
