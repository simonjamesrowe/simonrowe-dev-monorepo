package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.config.CveFixTaskQueues;
import com.simonrowe.factory.cvefix.dependencytrack.DependencyTrackClient;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRepository;
import io.temporal.spring.boot.ActivityImpl;
import java.util.List;
import org.springframework.stereotype.Component;

/** Reads findings and records scan outcomes; it has no repository mutation dependency. */
@Component
@ActivityImpl(taskQueues = CveFixTaskQueues.CVE_FIX)
public class CveFixActivitiesImpl implements CveFixActivities {

  private final DependencyTrackClient dependencyTrackClient;
  private final CveFixRunRepository runRepository;

  public CveFixActivitiesImpl(
      final DependencyTrackClient dependencyTrackClient,
      final CveFixRunRepository runRepository) {
    this.dependencyTrackClient = dependencyTrackClient;
    this.runRepository = runRepository;
  }

  @Override
  public List<ComponentFindings> fetchFindings() {
    return ComponentFindings.group(dependencyTrackClient.findings());
  }

  @Override
  public void recordRun(final CveFixRunRecord record) {
    runRepository.save(record);
  }
}
