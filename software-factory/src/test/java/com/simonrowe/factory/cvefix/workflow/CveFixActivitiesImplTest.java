package com.simonrowe.factory.cvefix.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.dependencytrack.DependencyTrackClient;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.domain.Finding;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CveFixActivitiesImplTest {

  private final DependencyTrackClient dependencyTrack = mock(DependencyTrackClient.class);
  private final CveFixRunRepository runs = mock(CveFixRunRepository.class);
  private final CveFixActivitiesImpl activities = new CveFixActivitiesImpl(dependencyTrack, runs);

  @Test
  void fetchFindingsGroupsEveryAdvisoryByComponent() {
    Finding first = finding("pkg:maven/a/a@1.0", "CVE-2026-1");
    Finding second = finding("pkg:maven/a/a@1.0", "CVE-2026-2");
    Finding third = finding("pkg:npm/b@2.0", "CVE-2026-3");
    when(dependencyTrack.findings()).thenReturn(List.of(first, second, third));

    List<ComponentFindings> result = activities.fetchFindings();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).vulnerabilityIds()).containsExactly("CVE-2026-1", "CVE-2026-2");
  }

  @Test
  void recordRunPersistsTheTerminalAuditRecord() {
    CveFixRunRecord record = new CveFixRunRecord(
        "workflow", "workflow", Instant.EPOCH, CveFixStatus.COMPLETED, 3,
        List.of(), null, 0, "filed", "run", 2, 1, 0, 0, 0,
        List.of("https://linear.app/issue/SIM-1"));

    activities.recordRun(record);

    verify(runs).save(record);
  }

  private static Finding finding(final String purl, final String id) {
    return new Finding(purl, purl, "1.0", id, "HIGH", "Upgrade the component");
  }
}
