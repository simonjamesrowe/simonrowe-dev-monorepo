package com.simonrowe.factoryadmin;

import java.time.Instant;
import java.util.List;

/**
 * The factory flow graph as the console consumes it.
 *
 * <p>Health, kind, band and loop are carried as strings rather than enums: this is a proxy, and a
 * value the factory adds later should reach the browser rather than failing deserialisation here.
 *
 * @param fetchedAt when the snapshot was taken
 * @param nodes every node
 * @param edges every edge
 */
public record FactoryFlow(Instant fetchedAt, List<Node> nodes, List<Edge> edges) {

  /**
   * One node.
   *
   * @param key the stable identifier
   * @param kind MODULE or ARTIFACT
   * @param band the row it is drawn in
   * @param label the human name
   * @param counts live figures, or null when the source could not be read
   * @param health the badge
   * @param diagnostic one sentence explaining a non-READY health, or null
   */
  public record Node(
      String key, String kind, String band, String label, Counts counts,
      String health, String diagnostic) {
  }

  /**
   * A node's figures.
   *
   * @param inFlight runs executing now
   * @param ok24h runs that succeeded in the last 24 hours
   * @param failed24h runs that failed in the last 24 hours
   */
  public record Counts(int inFlight, int ok24h, int failed24h) {
  }

  /**
   * A directed edge.
   *
   * @param from source node key
   * @param to target node key
   * @param label what travels along it
   * @param loop FAST, MAIN or SLOW
   */
  public record Edge(String from, String to, String label, String loop) {
  }
}
