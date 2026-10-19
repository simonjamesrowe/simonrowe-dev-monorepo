package com.simonrowe.factory.linear.domain;

import java.time.Instant;
import java.util.List;

/**
 * A Linear issue as the ticket agent reads it: everything a brief needs, plus the state and labels
 * that decide whether the agent may work on it.
 *
 * @param id the Linear issue UUID
 * @param identifier the human identifier, e.g. {@code SIM-42}
 * @param title the title
 * @param description the description in Markdown, never null
 * @param url the issue's web URL
 * @param stateType the workflow state's type
 * @param stateName the workflow state's name, e.g. {@code In Progress}
 * @param labels label names on the issue
 * @param priority Linear's priority integer: 0 none, 1 urgent, 2 high, 3 medium, 4 low
 * @param createdAt when the issue was created
 */
public record LinearTicket(
    String id,
    String identifier,
    String title,
    String description,
    String url,
    IssueStateType stateType,
    String stateName,
    List<String> labels,
    int priority,
    Instant createdAt) {

  public LinearTicket {
    description = description == null ? "" : description;
    labels = labels == null ? List.of() : List.copyOf(labels);
  }

  /**
   * Whether the issue carries a label, compared the way Linear's own filter does: ignoring case.
   *
   * @param name the label name
   * @return true when present
   */
  public boolean hasLabel(final String name) {
    return labels.stream().anyMatch(label -> label.equalsIgnoreCase(name));
  }
}
