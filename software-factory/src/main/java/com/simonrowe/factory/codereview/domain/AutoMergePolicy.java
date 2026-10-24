package com.simonrowe.factory.codereview.domain;

import com.simonrowe.factory.git.DependencyPins;
import com.simonrowe.factory.git.NpmLockPins;
import com.simonrowe.factory.git.PathGlobs;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Decides whether a reviewed pull request may have GitHub auto-merge armed.
 *
 * <p>Every rule can only say no. Each failing rule names itself as the reason, and the first one
 * to fail wins, so the reason a pull request was not armed is always a single sentence someone
 * can act on.
 *
 * <p>Deliberately <em>not</em> a rule: open review conversations. The {@code main} ruleset already
 * requires every conversation resolved before GitHub will merge, so arming with a
 * {@code SUGGESTION} outstanding is safe. It holds the merge until the thread is fixed or declined,
 * which is the behaviour wanted, and repeating the check here would be a second answer to a
 * question GitHub already owns.
 *
 * <p>Pure and static so the decision table is the unit under test. The file listing arrives as a
 * {@link Supplier} so that a pull request already disqualified by a cheaper rule costs no listing
 * at all, and so that ordering is part of what the tests can see.
 */
public final class AutoMergePolicy {

  /** A person's opt-out. Honoured on every review, so adding it and pushing withdraws a bot arm. */
  public static final String OPT_OUT_LABEL = "no-auto-merge";

  /**
   * Guidance pull requests from the review-feedback loop.
   *
   * <p>These used to be refused outright, on the grounds that agent instructions deserve a human's
   * eye. In practice every one of them was merged by hand unchanged, so the label now changes
   * <em>which</em> path rule applies rather than refusing: a guidance pull request is classified
   * against the distiller's own allowlist ({@code GuidancePaths}) instead of {@link
   * MergeDisposition}. That is both narrower and wider than the path rules. Narrower, because
   * root {@code *.md} other than {@code CLAUDE.md} no longer qualifies. Wider, because
   * {@code agent-setup}'s {@code components/instructions/global.md}, which the path rules call an
   * unrecognised (so manual) path, now does. Only this App's own pull requests get the guidance
   * rule: a person adding the label to their own change must not swap its path rules.
   */
  public static final String FEEDBACK_LABEL = "agent-feedback";

  /**
   * Permissions that mean the author could have pushed to this repository themselves. The
   * repository is public, so without this a stranger's pull request with a clean review would merge
   * itself. The API folds {@code maintain} into {@code write} and {@code triage} into {@code read}.
   *
   * <p>Not {@code author_association}: that is computed for the <em>viewer</em>, and a private
   * organisation membership is invisible to an App token, so the owner of this repository read as
   * {@code CONTRIBUTOR} and the first live pull request was refused (#194).
   */
  private static final Set<String> TRUSTED_PERMISSIONS = Set.of("admin", "write");

  private AutoMergePolicy() {
  }

  /**
   * Applies the rules.
   *
   * @param enabled {@code factory.codereview.auto-merge.enabled}
   * @param state the pull request as GitHub reports it now
   * @param report the review of {@code reviewedHeadSha}
   * @param reviewedHeadSha the commit the review was of
   * @param files fetched only if every earlier rule passes
   */
  public static MergeDecision decide(
      final boolean enabled,
      final AutoMergeState state,
      final ReviewReport report,
      final String reviewedHeadSha,
      final Supplier<ChangedFiles> files) {
    return decide(enabled, state, report, reviewedHeadSha, files, List.of());
  }

  /**
   * Applies the rules, with the guidance allowlist for this repository.
   *
   * @param enabled {@code factory.codereview.auto-merge.enabled}
   * @param state the pull request as GitHub reports it now
   * @param report the review of {@code reviewedHeadSha}
   * @param reviewedHeadSha the commit the review was of
   * @param files fetched only if every earlier rule passes
   * @param guidancePaths the globs a guidance pull request against this repository may touch;
   *     empty means no guidance pull request can qualify
   */
  public static MergeDecision decide(
      final boolean enabled,
      final AutoMergeState state,
      final ReviewReport report,
      final String reviewedHeadSha,
      final Supplier<ChangedFiles> files,
      final List<String> guidancePaths) {
    return decide(enabled, state, report, reviewedHeadSha, files, guidancePaths, path -> null);
  }

  /**
   * Applies the rules, with the guidance allowlist and a way to read build files at the head.
   *
   * @param enabled {@code factory.codereview.auto-merge.enabled}
   * @param state the pull request as GitHub reports it now
   * @param report the review of {@code reviewedHeadSha}
   * @param reviewedHeadSha the commit the review was of
   * @param files fetched only if every earlier rule passes
   * @param guidancePaths the globs a guidance pull request against this repository may touch
   * @param headContent a file's text at {@code reviewedHeadSha}, or null when unreadable; asked
   *     only when a factory pull request adds a dependency constraint
   */
  public static MergeDecision decide(
      final boolean enabled,
      final AutoMergeState state,
      final ReviewReport report,
      final String reviewedHeadSha,
      final Supplier<ChangedFiles> files,
      final List<String> guidancePaths,
      final UnaryOperator<String> headContent) {
    return decide(
        enabled, state, report, reviewedHeadSha, files, guidancePaths, headContent, null,
        ref -> null);
  }

  /**
   * Applies the rules, with a way to read the npm lockfile at a commit as well.
   *
   * @param enabled {@code factory.codereview.auto-merge.enabled}
   * @param state the pull request as GitHub reports it now
   * @param report the review of {@code reviewedHeadSha}
   * @param reviewedHeadSha the commit the review was of
   * @param files fetched only if every earlier rule passes
   * @param guidancePaths the globs a guidance pull request against this repository may touch
   * @param headContent a file's text at {@code reviewedHeadSha}, or null when unreadable; asked
   *     only when a factory pull request adds a dependency constraint
   * @param baseSha the pull request's base commit
   * @param lockfileAt {@link NpmLockPins#LOCKFILE}'s text at a commit, or null when unreadable;
   *     asked only when a factory pull request changes it, for the base and the reviewed head
   */
  public static MergeDecision decide(
      final boolean enabled,
      final AutoMergeState state,
      final ReviewReport report,
      final String reviewedHeadSha,
      final Supplier<ChangedFiles> files,
      final List<String> guidancePaths,
      final UnaryOperator<String> headContent,
      final String baseSha,
      final UnaryOperator<String> lockfileAt) {
    if (!enabled) {
      return MergeDecision.off();
    }
    if (state.draft()) {
      return MergeDecision.ineligible("the pull request is a draft");
    }
    if (state.crossRepository()) {
      return MergeDecision.ineligible("the change comes from a fork");
    }
    if (!state.authorIsThisApp()
        && (state.authorPermission() == null
            || !TRUSTED_PERMISSIONS.contains(state.authorPermission()))) {
      return MergeDecision.ineligible(
          "the author has no write access to this repository (`%s`)"
              .formatted(state.authorPermission()));
    }
    if (state.labels().contains(OPT_OUT_LABEL)) {
      return MergeDecision.ineligible("labelled `" + OPT_OUT_LABEL + "`");
    }
    boolean guidance = state.labels().contains(FEEDBACK_LABEL);
    if (guidance && !state.authorIsThisApp()) {
      return MergeDecision.ineligible(
          "labelled `" + FEEDBACK_LABEL + "` but not opened by the factory");
    }
    if (CheckRunConclusion.from(report.verdict(), report.findings())
        != CheckRunConclusion.SUCCESS) {
      return MergeDecision.ineligible("the `Code Review` check is red");
    }
    if (!Objects.equals(state.headSha(), reviewedHeadSha)) {
      return MergeDecision.ineligible(
          "the pull request moved on to `%s` after `%s` was reviewed"
              .formatted(shortSha(state.headSha()), shortSha(reviewedHeadSha)));
    }

    ChangedFiles changed = files.get();
    if (!changed.complete()) {
      return MergeDecision.ineligible(
          "the file list is too long to classify (" + state.changedFiles() + " files)");
    }
    if (guidance) {
      return guidanceDecision(changed, guidancePaths);
    }
    MergeDisposition.Classification classification = MergeDisposition.classify(changed.paths());
    if (classification.disposition() == MergeDisposition.MANUAL && state.authorIsThisApp()) {
      Optional<MergeDecision> pins =
          pinDecision(changed, headContent, lockfileAt, baseSha, reviewedHeadSha);
      if (pins.isPresent()) {
        return pins.get();
      }
    }
    return pathDecision(classification);
  }

  private static MergeDecision pathDecision(
      final MergeDisposition.Classification classification) {
    return switch (classification.disposition()) {
      case AUTO_MERGE -> MergeDecision.eligible();
      case UX_REVIEW ->
          MergeDecision.ineligible(
              "`%s` changes what a visitor sees, so it needs screenshots and a human"
                  .formatted(classification.decidingPath()));
      case MANUAL ->
          classification.decidingPath() == null
              ? MergeDecision.ineligible("the change touches no files")
              : MergeDecision.ineligible(
                  "`" + classification.decidingPath() + "` needs a human to merge");
    };
  }

  /**
   * A guidance pull request qualifies when every path is one the distiller may write.
   *
   * <p>An empty listing qualifies nothing, for the same reason {@link MergeDisposition} calls it
   * manual: "every path is allowed" is vacuously true of no paths.
   */
  private static MergeDecision guidanceDecision(
      final ChangedFiles changed, final List<String> guidancePaths) {
    if (changed.paths().isEmpty()) {
      return MergeDecision.ineligible("the change touches no files");
    }
    return PathGlobs.outside(changed.paths(), guidancePaths).stream()
        .findFirst()
        .map(path -> MergeDecision.ineligible(
            "`" + path + "` is outside the guidance allowlist"))
        .orElseGet(MergeDecision::eligible);
  }

  /**
   * The factory's own CVE repair: build files or the npm lockfile that only move versions, plus
   * nothing a person must merge.
   *
   * <p>Only for this App's pull requests, which the ticket agent opens, and which it labels
   * {@code no-auto-merge} unless the ticket is a vulnerability repair. So a person's dependency
   * edit, and the agent's edit for any other kind of ticket, still needs a person. Paths other
   * than the pin files keep their ordinary rules: a version bump plus a backend fix can merge, a
   * version bump plus a compose change cannot.
   *
   * @return the decision, or empty when this rule does not apply and the ordinary reason stands
   */
  private static Optional<MergeDecision> pinDecision(
      final ChangedFiles changed,
      final UnaryOperator<String> headContent,
      final UnaryOperator<String> lockfileAt,
      final String baseSha,
      final String headSha) {
    List<String> others =
        changed.paths().stream()
            .filter(path -> !DependencyPins.isPinFile(path) && !NpmLockPins.isLockfile(path))
            .toList();
    if (others.size() == changed.paths().size()) {
      return Optional.empty();
    }
    if (!others.isEmpty()) {
      MergeDisposition.Classification rest = MergeDisposition.classify(others);
      if (rest.disposition() != MergeDisposition.AUTO_MERGE) {
        // Name what actually needs a person, not the pin file that would have qualified.
        return Optional.of(pathDecision(rest));
      }
    }
    List<DependencyPins.FileDiff> pinDiffs =
        changed.diffs().stream()
            .filter(diff -> DependencyPins.isPinFile(diff.path())
                || DependencyPins.isPinFile(diff.previousPath()))
            .toList();
    // Decided by which paths changed, never by which patches GitHub supplied: a pin file whose
    // patch was omitted must still be checked, and DependencyPins refuses an empty list.
    if (changed.paths().stream().anyMatch(DependencyPins::isPinFile)) {
      DependencyPins.Verdict verdict = DependencyPins.check(pinDiffs, headContent);
      if (!verdict.versionsOnly()) {
        return Optional.of(MergeDecision.ineligible(
            "the dependency change is more than a version bump: " + verdict.reason()));
      }
    }
    List<DependencyPins.FileDiff> lockDiffs =
        changed.diffs().stream()
            .filter(diff -> NpmLockPins.isLockfile(diff.path())
                || NpmLockPins.isLockfile(diff.previousPath()))
            .toList();
    if (changed.paths().stream().anyMatch(NpmLockPins::isLockfile)) {
      Optional<String> refused = lockfileProblem(lockDiffs, lockfileAt, baseSha, headSha);
      if (refused.isPresent()) {
        return Optional.of(MergeDecision.ineligible(
            "the lockfile change is more than a version bump: " + refused.get()));
      }
    }
    return Optional.of(MergeDecision.eligible());
  }

  /**
   * Why a lockfile change is refused, or empty when it only moves packages up.
   *
   * <p>Compared as whole files at the base and the reviewed head, never from GitHub's patch, which
   * it omits for a diff this size. A base that has moved on since the branch was cut shows the
   * other change reversed, which is refused: safe, and the branch conflicts anyway.
   */
  private static Optional<String> lockfileProblem(
      final List<DependencyPins.FileDiff> lockDiffs,
      final UnaryOperator<String> lockfileAt,
      final String baseSha,
      final String headSha) {
    if (lockDiffs.size() != 1) {
      return Optional.of("GitHub listed `%s` %d times".formatted(NpmLockPins.LOCKFILE,
          lockDiffs.size()));
    }
    DependencyPins.FileDiff diff = lockDiffs.get(0);
    if (!"modified".equals(diff.status())
        || (diff.previousPath() != null && !diff.previousPath().isEmpty())) {
      return Optional.of("`%s` is %s, not modified".formatted(NpmLockPins.LOCKFILE, diff.status()));
    }
    if (baseSha == null || baseSha.isBlank()) {
      return Optional.of("the pull request's base commit is unknown");
    }
    NpmLockPins.Verdict verdict =
        NpmLockPins.check(lockfileAt.apply(baseSha), lockfileAt.apply(headSha));
    return verdict.versionsOnly() ? Optional.empty() : Optional.of(verdict.reason());
  }

  private static String shortSha(final String sha) {
    return sha == null || sha.length() <= 7 ? String.valueOf(sha) : sha.substring(0, 7);
  }
}
