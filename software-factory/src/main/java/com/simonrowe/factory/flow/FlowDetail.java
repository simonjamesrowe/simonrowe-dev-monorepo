package com.simonrowe.factory.flow;

import java.time.Instant;
import java.util.List;

/**
 * The work behind one node, for its drawer.
 *
 * @param nodeKey the node this describes
 * @param items the work, newest first; null when this node has its own reader (an artifact node)
 *     and that reader could not read its source; empty when the source was read and genuinely
 *     has nothing open or running. See {@code FactoryFlowDetailService} for which nodes carry a
 *     reader that can produce null here.
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
   * @param agent where the ticket agent stands with this item. For a Linear ticket: {@code
   *     with-agent} (labelled for it) or {@code stuck} (handed back after failing). For one of the
   *     agent's own runs: {@code running} while it is live. Null otherwise. The console offers
   *     "Send to agent" on a null ticket, "Retry" on a stuck one and "Stop" on a running run
   */
  public record Item(
      String id, String title, String status, Instant at, String url, String agent) {

    public static final String WITH_AGENT = "with-agent";
    public static final String STUCK = "stuck";
    public static final String RUNNING = "running";

    /** Anything that is not a ticket the agent could take. */
    public Item(
        final String id, final String title, final String status, final Instant at,
        final String url) {
      this(id, title, status, at, url, null);
    }

    Item withAgent(final String standing) {
      return new Item(id, title, status, at, url, standing);
    }
  }

  /**
   * Nothing to show — a node with no list of its own ({@code production}, an unrecognised key).
   * These are the only cases that genuinely mean "there is nothing here".
   *
   * <p>Deliberately not used when a module's Temporal query fails or an artifact node's own
   * reader fails: {@code FactoryFlowDetailService} builds a {@link FlowDetail} with null {@code
   * items} for both of those cases instead, so an unreadable source is never presented as a quiet
   * one.
   */
  public static FlowDetail empty(final String nodeKey) {
    return new FlowDetail(nodeKey, List.of());
  }
}
