package com.simonrowe.factory.pageread.workflow;

import com.simonrowe.factory.pageread.domain.PageReadInput;
import com.simonrowe.factory.pageread.domain.PageReadProgress;
import com.simonrowe.factory.pageread.domain.PageReadResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Reads one page in the page-reader's headless browser: address in, article text out. */
@WorkflowInterface
public interface PageReadWorkflow {

  @WorkflowMethod
  PageReadResult read(PageReadInput input);

  /** Named {@code progress} like every factory workflow, which the console's run view relies on. */
  @QueryMethod
  PageReadProgress progress();
}
