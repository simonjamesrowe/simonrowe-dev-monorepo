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
 * @param mode how this occurrence should be handled once an issue for the problem already
 *     exists; see {@link FilingMode} for what each value means
 */
public record IssueFiling(
    String producer,
    List<String> keyParts,
    String title,
    String body,
    String occurrenceDetail,
    String occurrenceId,
    String workflowId,
    FilingMode mode) {

  public IssueFiling {
    keyParts = keyParts == null ? List.of() : List.copyOf(keyParts);
    mode = mode == null ? FilingMode.OCCURRENCE : mode;
  }

  /**
   * Files an ordinary occurrence, which may create an issue.
   *
   * @param producer the producer key
   * @param keyParts the structured parts identifying the problem
   * @param title the issue title
   * @param body the issue description in Markdown
   * @param occurrenceDetail one line naming this occurrence
   * @param occurrenceId the producing workflow's run id
   * @param workflowId the producing workflow's id
   */
  public IssueFiling(
      final String producer,
      final List<String> keyParts,
      final String title,
      final String body,
      final String occurrenceDetail,
      final String occurrenceId,
      final String workflowId) {
    this(
        producer,
        keyParts,
        title,
        body,
        occurrenceDetail,
        occurrenceId,
        workflowId,
        FilingMode.OCCURRENCE);
  }
}
