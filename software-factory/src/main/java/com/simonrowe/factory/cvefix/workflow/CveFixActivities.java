package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

/** Network and persistence operations for the issue-only vulnerability scan. */
@ActivityInterface
public interface CveFixActivities {

  /** Reads every current Dependency-Track finding, grouped by component PURL. */
  @ActivityMethod
  List<ComponentFindings> fetchFindings();

  /** Persists one terminal scan record. */
  @ActivityMethod
  void recordRun(CveFixRunRecord record);

  /**
   * Whether the scan before this one found any findings.
   *
   * <p>Read only on the empty branch, to tell a repository that has just become clean from one
   * that was already clean. Without it, every nightly scan of a clean repository would post
   * another "no current vulnerabilities" comment, forever.
   *
   * @param workflowId this run's workflow id, excluded so a re-drive does not read its own row
   * @return true when the previous run recorded at least one finding
   */
  @ActivityMethod
  boolean previousScanFoundFindings(String workflowId);
}
