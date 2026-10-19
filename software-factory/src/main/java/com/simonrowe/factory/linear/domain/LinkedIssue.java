package com.simonrowe.factory.linear.domain;

import java.util.List;

/**
 * A Linear issue found by one of its attachment URLs, with every URL attached to it.
 *
 * <p>The attachments are what tie a feedback ticket to its guidance pull requests: the feedback
 * workflow attaches each one as it opens it, so the ticket itself is the record of which pull
 * requests have to merge before it is done.
 *
 * @param id the Linear issue UUID
 * @param identifier the human identifier, e.g. {@code SIM-42}
 * @param url the issue's web URL
 * @param stateType the issue's current workflow state type
 * @param attachmentUrls every URL attached to the issue, the one searched for included
 */
public record LinkedIssue(
    String id, String identifier, String url, IssueStateType stateType,
    List<String> attachmentUrls) {

  public LinkedIssue {
    attachmentUrls = attachmentUrls == null ? List.of() : List.copyOf(attachmentUrls);
  }
}
