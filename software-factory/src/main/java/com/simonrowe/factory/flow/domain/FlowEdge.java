package com.simonrowe.factory.flow.domain;

/**
 * A directed edge between two nodes.
 *
 * @param from the source node key
 * @param to the target node key
 * @param label what travels along it
 * @param loop which feedback loop it belongs to
 */
public record FlowEdge(String from, String to, String label, Loop loop) {
}
