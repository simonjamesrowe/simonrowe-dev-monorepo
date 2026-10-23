package com.simonrowe.factory.staticanalysis.workflow;

import com.simonrowe.factory.staticanalysis.domain.AnalysisHealth;
import com.simonrowe.factory.staticanalysis.domain.FindingGroup;
import com.simonrowe.factory.staticanalysis.domain.SonarProject;
import java.util.List;

/**
 * Everything one scan read, returned by the activity to the workflow.
 *
 * <p>When {@link AnalysisHealth#usable()} is false nothing else was read, and every count is
 * zero. A caller must check the health first; that is why it is the first field.
 *
 * @param health the analysis verdict
 * @param project where the analysis lives, for links in ticket bodies
 * @param groups the finding groups to file this run, already capped and in filing order
 * @param groupsDropped how many further groups the per-run cap left unfiled
 * @param droppedKeyParts the key parts of exactly those dropped groups, so the absence sweep
 *     leaves their tickets open. Same contract as log watch's {@code droppedKeyParts}
 * @param issuesSeen open new-code issues read, before the severity floor
 * @param issuesBelowFloor of those, code smells under the floor and so not filed
 * @param truncated whether the issue read stopped short of SonarCloud's total, in which case an
 *     absence proves nothing and the sweep must not run
 */
public record AnalysisObservation(
    AnalysisHealth health,
    SonarProject project,
    List<FindingGroup> groups,
    int groupsDropped,
    List<List<String>> droppedKeyParts,
    int issuesSeen,
    int issuesBelowFloor,
    boolean truncated) {

  public AnalysisObservation {
    groups = groups == null ? List.of() : List.copyOf(groups);
    droppedKeyParts =
        droppedKeyParts == null
            ? List.of()
            : droppedKeyParts.stream().map(List::copyOf).toList();
  }

  /**
   * An observation that stopped at the health check.
   *
   * @param health the verdict that stopped it
   * @param project where the analysis lives
   * @return the observation
   */
  public static AnalysisObservation unusable(
      final AnalysisHealth health, final SonarProject project) {
    return new AnalysisObservation(health, project, List.of(), 0, List.of(), 0, 0, false);
  }

  /**
   * How many groups the analysis produced before the cap.
   *
   * @return groups filed plus groups dropped
   */
  public int groupsFound() {
    return groups.size() + groupsDropped;
  }
}
