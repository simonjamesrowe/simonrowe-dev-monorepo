package com.simonrowe.factory.codereview.domain;

import java.util.List;

/**
 * Every path a pull request touches, for the merge-disposition check.
 *
 * <p>Includes the <em>old</em> path of every rename. Moving {@code scripts/x.sh} to
 * {@code docs/x.sh} deletes a script, and classifying only the destination would call that a
 * docs change.
 *
 * <p>Deliberately not the review workspace's own changed-file list: that one is filtered to paths
 * safe to hand an agent and capped at {@code maxChangedFiles}, so it can omit exactly the paths
 * this check exists to catch.
 *
 * @param paths every path, old and new
 * @param complete false when the listing stopped short of GitHub's own {@code changed_files} count
 *     — GitHub serves at most 3000 files — in which case nothing may be armed from it
 */
public record ChangedFiles(List<String> paths, boolean complete) {

  public ChangedFiles {
    paths = paths == null ? List.of() : List.copyOf(paths);
  }
}
