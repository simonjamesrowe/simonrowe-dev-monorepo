package com.simonrowe.factory.codereview.agent;

import com.simonrowe.factory.codereview.domain.PriorFinding;
import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import java.util.List;
import java.util.function.Consumer;

/** Provider-neutral seam for code-aware review harnesses. */
public interface ReviewEngine {

  /**
   * Reviews the pull request at its head.
   *
   * @param prior this reviewer's findings still open on the pull request, or declined on it by a
   *     person. Open ones keep their identity when reported again; declined ones are never
   *     reported again
   */
  ReviewReport review(
      PullRequestContext pullRequest, List<PriorFinding> prior, Consumer<String> heartbeat);
}
