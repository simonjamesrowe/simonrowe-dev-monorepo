package com.simonrowe.factory.writing.workflow;

import com.simonrowe.factory.writing.domain.WritingRequest;
import com.simonrowe.factory.writing.domain.WritingResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** The one side effect of a writing job: a single tool-less Claude Code run. */
@ActivityInterface
public interface ContentWritingActivities {

  @ActivityMethod
  WritingResult write(WritingRequest request);
}
