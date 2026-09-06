package com.simonrowe.factory.flow;

import com.simonrowe.factory.flow.domain.FlowEdge;
import java.time.Instant;
import java.util.List;

/**
 * The whole graph, as one read.
 *
 * @param fetchedAt when this snapshot was taken
 * @param nodes every node with its live figures
 * @param edges every edge; fixed, and identical on every call
 */
public record FactoryFlowResponse(Instant fetchedAt, List<FlowNode> nodes, List<FlowEdge> edges) {
}
