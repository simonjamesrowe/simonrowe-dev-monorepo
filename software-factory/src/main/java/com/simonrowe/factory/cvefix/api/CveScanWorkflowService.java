package com.simonrowe.factory.cvefix.api;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.domain.CveFixProgress;
import com.simonrowe.factory.cvefix.domain.CveFixRequest;
import com.simonrowe.factory.cvefix.workflow.CveFixWorkflow;
import com.simonrowe.factory.linear.config.LinearProperties;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Starts and queries manually requested issue-only vulnerability scans. */
@Service
public class CveScanWorkflowService {

  private final WorkflowClient client;
  private final LinearProperties linear;

  public CveScanWorkflowService(final WorkflowClient client, final LinearProperties linear) {
    this.client = client;
    this.linear = linear;
  }

  public CveScanAccepted start() {
    String workflowId = "cve-scan-manual-" + UUID.randomUUID();
    CveFixWorkflow workflow =
        client.newWorkflowStub(
            CveFixWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(CveFixTaskQueues.CVE_FIX)
                .setWorkflowIdReusePolicy(
                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
    WorkflowExecution execution =
        WorkflowClient.start(
            workflow::run,
            new CveFixRequest(false, null, 0, null, linear.enabled()));
    return new CveScanAccepted(execution.getWorkflowId(), execution.getRunId(),
        "Vulnerability scan accepted");
  }

  public CveFixProgress progress(final String workflowId) {
    return client.newWorkflowStub(CveFixWorkflow.class, workflowId).progress();
  }
}
