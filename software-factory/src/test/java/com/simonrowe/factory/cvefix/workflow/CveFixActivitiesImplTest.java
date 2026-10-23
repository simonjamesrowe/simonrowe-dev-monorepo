package com.simonrowe.factory.cvefix.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.config.CveRepairProperties;
import com.simonrowe.factory.cvefix.dependencytrack.DependencyTrackClient;
import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.CveFixStatus;
import com.simonrowe.factory.cvefix.domain.Finding;
import com.simonrowe.factory.cvefix.domain.RepairPlan;
import com.simonrowe.factory.cvefix.osv.OsvClient;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRecord;
import com.simonrowe.factory.cvefix.persistence.CveFixRunRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class CveFixActivitiesImplTest {

  private final DependencyTrackClient dependencyTrack = mock(DependencyTrackClient.class);
  private final CveFixRunRepository runs = mock(CveFixRunRepository.class);
  private final OsvClient osv = mock(OsvClient.class);
  private final CveFixActivitiesImpl activities = new CveFixActivitiesImpl(
      dependencyTrack, runs, osv, new CveRepairProperties(true, null, null, null, null));

  @Test
  void plansOnlyBackendMavenComponentsAndSkipsOnesOsvCannotAnswer() {
    ComponentFindings fixable = component("simonrowe-dev/backend",
        "pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.25?type=jar", "11.0.25");
    ComponentFindings unreachable = component("simonrowe-dev/backend",
        "pkg:maven/io.netty/netty-codec@4.1.100.Final", "4.1.100.Final");
    ComponentFindings npm = component("simonrowe-dev/frontend", "pkg:npm/vite@6.0.0", "6.0.0");
    when(osv.advisories("org.apache.tomcat.embed:tomcat-embed-core", "11.0.25"))
        .thenReturn(List.of(new OsvClient.Advisory("GHSA-1", "11.0.26")));
    when(osv.advisories("io.netty:netty-codec", "4.1.100.Final"))
        .thenThrow(new IllegalStateException("OSV returned 503"));

    List<RepairPlan> plans = activities.planRepairs(List.of(fixable, unreachable, npm));

    assertThat(plans).singleElement().satisfies(plan -> {
      assertThat(plan.coordinate()).isEqualTo("org.apache.tomcat.embed:tomcat-embed-core");
      assertThat(plan.targetVersion()).isEqualTo("11.0.26");
    });
    verify(osv, never()).advisories(eq("vite"), any());
  }

  private static ComponentFindings component(
      final String project, final String purl, final String version) {
    return new ComponentFindings(project, purl, "x", version, List.of("GHSA-1"),
        List.of(new Finding(project, purl, "x", version, "GHSA-1", "HIGH", "")));
  }

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

  private static final EnumSet<CveFixStatus> OBSERVED =
      EnumSet.of(CveFixStatus.COMPLETED, CveFixStatus.NO_FINDINGS);

  @Test
  void previousScanFoundFindingsIsTrueWhenTheLastRunSawSomething() {
    when(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("wf-2", OBSERVED))
        .thenReturn(java.util.Optional.of(
            new CveFixRunRecord(
                "wf-1", "wf-1", Instant.EPOCH, CveFixStatus.COMPLETED, 4,
                List.of(), null, 0, "detail")));

    assertThat(activities.previousScanFoundFindings("wf-2")).isTrue();
  }

  @Test
  void previousScanFoundFindingsIsFalseWhenTheLastRunWasCleanOrThereWasNone() {
    when(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("wf-2", OBSERVED))
        .thenReturn(java.util.Optional.of(
            new CveFixRunRecord(
                "wf-1", "wf-1", Instant.EPOCH, CveFixStatus.NO_FINDINGS, 0,
                List.of(), null, 0, "detail")));
    assertThat(activities.previousScanFoundFindings("wf-2")).isFalse();

    when(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("wf-3", OBSERVED))
        .thenReturn(java.util.Optional.empty());
    assertThat(activities.previousScanFoundFindings("wf-3")).isFalse();
  }

  @Test
  void previousScanFoundFindingsIgnoresFailedRunAndFallsBackToTheLastObservedOne() {
    // Regression test for the bug FIX 1 addresses: a FAILED row (Dependency-Track down) must
    // never be read as "the previous scan was clean".
    when(runs.findFirstByIdNotAndStatusInOrderByStartedAtDesc("wf-3", OBSERVED))
        .thenReturn(java.util.Optional.of(
            new CveFixRunRecord(
                "wf-1", "wf-1", Instant.EPOCH, CveFixStatus.COMPLETED, 61,
                List.of(), null, 0, "detail")));

    assertThat(activities.previousScanFoundFindings("wf-3")).isTrue();
  }

  private static Finding finding(final String purl, final String id) {
    return new Finding("simonrowe-dev/backend", purl, purl, "1.0", id, "HIGH",
        "Upgrade the component");
  }
}
