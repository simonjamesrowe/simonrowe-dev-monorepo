package com.simonrowe.factory.flow;

import java.time.Instant;
import java.util.List;

/**
 * The work behind one node, for its drawer.
 *
 * @param nodeKey the node this describes
 * @param items the work, newest first; empty when there is none or the source could not be read
 */
public record FlowDetail(String nodeKey, List<Item> items) {

  /**
   * One piece of work.
   *
   * @param id the workflow id, pull request number or Linear identifier
   * @param title a human-readable label — for a workflow run, the module's name plus its start
   *     time, deliberately not a repeat of {@code id}: two runs of the same module must be
   *     distinguishable by more than comparing hashes
   * @param status its state, already normalised to a word an operator reads
   * @param at when it started or was last seen, or null
   * @param url somewhere to open it, or null
   */
  public record Item(String id, String title, String status, Instant at, String url) {
  }

  /** Nothing to show. Used for artifact nodes with no list and for an unreadable source alike. */
  public static FlowDetail empty(final String nodeKey) {
    return new FlowDetail(nodeKey, List.of());
  }
}
