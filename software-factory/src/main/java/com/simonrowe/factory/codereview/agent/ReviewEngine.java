package com.simonrowe.factory.codereview.agent;

import com.simonrowe.factory.codereview.domain.PullRequestContext;
import com.simonrowe.factory.codereview.domain.ReviewReport;
import java.util.function.Consumer;

/** Provider-neutral seam for code-aware review harnesses. */
public interface ReviewEngine {

  ReviewReport review(PullRequestContext pullRequest, Consumer<String> heartbeat);
}
