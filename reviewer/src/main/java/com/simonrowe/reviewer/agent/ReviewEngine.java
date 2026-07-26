package com.simonrowe.reviewer.agent;

import com.simonrowe.reviewer.domain.PullRequestContext;
import com.simonrowe.reviewer.domain.ReviewReport;
import java.util.function.Consumer;

/** Provider-neutral seam for code-aware review harnesses. */
public interface ReviewEngine {

  ReviewReport review(PullRequestContext pullRequest, Consumer<String> heartbeat);
}
