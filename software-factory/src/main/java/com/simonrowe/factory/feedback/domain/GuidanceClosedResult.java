package com.simonrowe.factory.feedback.domain;

import java.util.List;

/**
 * What closing a guidance pull request did to its tickets.
 *
 * @param completed identifiers of tickets moved to done, because every guidance pull request
 *     attached to them has now merged
 * @param waiting identifiers of tickets left open because another of their guidance pull requests
 *     has not merged yet, or because this one closed without merging
 */
public record GuidanceClosedResult(List<String> completed, List<String> waiting) {

  public GuidanceClosedResult {
    completed = completed == null ? List.of() : List.copyOf(completed);
    waiting = waiting == null ? List.of() : List.copyOf(waiting);
  }
}
