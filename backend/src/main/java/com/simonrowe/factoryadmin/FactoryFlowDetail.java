package com.simonrowe.factoryadmin;

import java.time.Instant;
import java.util.List;

/**
 * The work behind one flow node, as the console consumes it.
 *
 * <p>Mirrors software-factory's own {@code FlowDetail}: {@code items} may be null, meaning this
 * node's own source could not be read — either software-factory's artifact reader (Linear,
 * GitHub) failed, or, for {@code deploy}/{@code platformbackup}, the deployer itself could not be
 * asked at all. It is empty, not null, when the source was read and genuinely has nothing open or
 * running. Collapsing the two would misreport a broken read as a quiet one, which is exactly the
 * bug {@link #unavailable(String)} exists to stop.
 *
 * @param nodeKey the node this describes
 * @param items the work, newest first; null when the source could not be read; empty when it was
 *     read and there is genuinely nothing to show
 */
public record FactoryFlowDetail(String nodeKey, List<Item> items) {

  /**
   * One piece of work.
   *
   * @param id the workflow id, pull request number or Linear identifier
   * @param title what it is
   * @param status its state, already normalised to a word an operator reads
   * @param at when it started or was last seen, or null
   * @param url somewhere to open it, or null
   */
  public record Item(String id, String title, String status, Instant at, String url) {
  }

  /**
   * The source was read successfully and genuinely has nothing to show.
   *
   * @param nodeKey the node this describes
   * @return an empty detail for that node
   */
  public static FactoryFlowDetail empty(final String nodeKey) {
    return new FactoryFlowDetail(nodeKey, List.of());
  }

  /**
   * The source could not be read at all — the downstream call failed, or the owning container
   * could not be reached — as distinct from {@link #empty(String)}, which means the read
   * succeeded and found nothing. Reporting this as empty would tell an operator a node has no
   * recent work when the truth is the console simply could not find out.
   *
   * @param nodeKey the node this describes
   * @return a detail for that node whose {@link #items()} is null
   */
  public static FactoryFlowDetail unavailable(final String nodeKey) {
    return new FactoryFlowDetail(nodeKey, null);
  }
}
