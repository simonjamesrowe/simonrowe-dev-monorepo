package com.simonrowe.factory.flow;

import com.simonrowe.factory.flow.domain.Band;
import com.simonrowe.factory.flow.domain.NodeCounts;
import com.simonrowe.factory.flow.domain.NodeHealth;
import com.simonrowe.factory.flow.domain.NodeKind;

/**
 * One node as the console renders it.
 *
 * @param key the stable identifier edges refer to
 * @param kind module or artifact
 * @param band the row it is drawn in
 * @param label the human name
 * @param counts live figures, or null when the source could not be read
 * @param health the badge
 * @param diagnostic one sentence explaining a non-READY health, or null
 */
public record FlowNode(
    String key,
    NodeKind kind,
    Band band,
    String label,
    NodeCounts counts,
    NodeHealth health,
    String diagnostic) {
}
