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
}
