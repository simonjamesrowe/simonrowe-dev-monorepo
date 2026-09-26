package com.simonrowe.factory;

import com.simonrowe.factory.codereview.workflow.CodeReviewWorkflowImpl;
import com.simonrowe.factory.cvefix.workflow.CveFixWorkflowImpl;
import com.simonrowe.factory.deploy.workflow.DeployWorkflowImpl;
import com.simonrowe.factory.feedback.workflow.ReviewFeedbackWorkflowImpl;
import com.simonrowe.factory.logwatch.workflow.LogWatchWorkflowImpl;
import com.simonrowe.factory.platformbackup.workflow.PlatformBackupWorkflowImpl;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate.RegisteredInfo;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads which workflow implementations a context registered with its Temporal workers. */
final class RegisteredWorkflows {

  static final List<Class<?>> ALL =
      List.of(
          CodeReviewWorkflowImpl.class,
          ReviewFeedbackWorkflowImpl.class,
          CveFixWorkflowImpl.class,
          LogWatchWorkflowImpl.class,
          DeployWorkflowImpl.class,
          PlatformBackupWorkflowImpl.class);

  private RegisteredWorkflows() {
  }

  /**
   * Returns every workflow implementation registered on any of the context's workers.
   *
   * <p>A worker can exist for a queue with no workflow on it at all (an activity bean creates one),
   * so the worker list cannot answer this; the registration info can.
   */
  static Set<Class<?>> in(final WorkersTemplate workersTemplate) {
    return ALL.stream()
        .filter(
            implementation ->
                workersTemplate.getRegisteredInfo().values().stream()
                    .anyMatch((RegisteredInfo info) -> info.isWorkflowRegistered(implementation)))
        .collect(Collectors.toSet());
  }
}
