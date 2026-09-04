package com.simonrowe.factoryadmin;

import java.time.Instant;
import java.util.List;

/**
 * The work behind one flow node, as the console consumes it.
 *
 * @param nodeKey the node this describes
 * @param items the work, newest first; empty when there is none or the source could not be read
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
   * Nothing to show.
   *
   * @param nodeKey the node this describes
   * @return an empty detail for that node
   */
  public static FactoryFlowDetail empty(final String nodeKey) {
    return new FactoryFlowDetail(nodeKey, List.of());
  }
}
