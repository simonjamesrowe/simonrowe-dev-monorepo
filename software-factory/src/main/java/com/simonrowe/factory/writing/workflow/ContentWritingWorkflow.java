package com.simonrowe.factory.writing.workflow;

import com.simonrowe.factory.writing.domain.WritingProgress;
import com.simonrowe.factory.writing.domain.WritingRequest;
import com.simonrowe.factory.writing.domain.WritingResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Writes one piece of text with Claude Code: prompt in, text out. */
@WorkflowInterface
public interface ContentWritingWorkflow {

  @WorkflowMethod
  WritingResult write(WritingRequest request);

  /** Named {@code progress} like every factory workflow, which the console's run view relies on. */
  @QueryMethod
  WritingProgress progress();
}
