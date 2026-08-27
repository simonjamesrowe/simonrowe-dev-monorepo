package com.simonrowe.factory.linear.domain;

import java.util.List;

/**
 * One occurrence a producer wants filed.
 *
 * @param producer the producer key, e.g. {@code deploy} — selects the label and priority
 * @param keyParts the structured parts identifying the problem; never agent prose
 * @param title the issue title, which may be agent prose
 * @param body the issue description in Markdown, used only when an issue is created
 * @param occurrenceDetail one line naming this occurrence, used only when commenting
 * @param occurrenceId the producing workflow's run id, so an activity replay is recognised
 * @param workflowId the producing workflow's id, recorded in the audit trail
 */
public record IssueFiling(
    String producer,
    List<String> keyParts,
    String title,
    String body,
    String occurrenceDetail,
    String occurrenceId,
    String workflowId) {

  public IssueFiling {
    keyParts = keyParts == null ? List.of() : List.copyOf(keyParts);
  }
}
