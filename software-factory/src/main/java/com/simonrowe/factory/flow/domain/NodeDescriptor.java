package com.simonrowe.factory.flow.domain;

/**
 * A node's fixed properties: everything true of it before any live data is read.
 *
 * @param key the stable identifier used by edges and by the frontend
 * @param kind whether the factory runs this or exchanges it
 * @param band the row it is drawn in
 * @param label the human name
 * @param moduleKey the {@code ModulePrerequisites} key whose health this node reports, or null
 *     when no module owns it. Set on the {@code linear} artifact node, which reports the
 *     activity-only {@code linear} module's health because that module is not drawn as a box.
 * @param workflowType the Temporal workflow type whose executions this node counts, or null
 */
public record NodeDescriptor(
    String key,
    NodeKind kind,
    Band band,
    String label,
    String moduleKey,
    String workflowType) {
}
