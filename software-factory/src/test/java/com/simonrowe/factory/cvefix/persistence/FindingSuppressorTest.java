package com.simonrowe.factory.cvefix.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
import com.simonrowe.factory.cvefix.domain.UnfixableComponent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FindingSuppressorTest {

  @Mock private UnfixableFindingRepository repository;
  @InjectMocks private FindingSuppressor suppressor;

  private static ComponentFindings component(final String purl, final String... ids) {
    return new ComponentFindings(purl, "name", "1.0", List.of(ids), List.of());
  }

  private static UnfixableFindingRecord record(final String purl, final String fingerprint) {
    return new UnfixableFindingRecord(
        purl, purl, fingerprint, List.of(), "no fix", Instant.EPOCH);
  }

  @Test
  void retainsComponentsWithNoRecord() {
    when(repository.findByPurl("pkg:maven/a/b@1")).thenReturn(Optional.empty());

    assertThat(suppressor.retainActionable(List.of(component("pkg:maven/a/b@1", "CVE-1"))))
        .hasSize(1);
  }

  @Test
  void skipsComponentsWhoseFingerprintIsUnchanged() {
    when(repository.findByPurl("pkg:maven/a/b@1"))
        .thenReturn(Optional.of(record("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1")));

    assertThat(suppressor.retainActionable(List.of(component("pkg:maven/a/b@1", "CVE-1"))))
        .isEmpty();
  }

  @Test
  void retainsComponentsWhenNewAdvisoryAppears() {
    when(repository.findByPurl("pkg:maven/a/b@1"))
        .thenReturn(Optional.of(record("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1")));

    assertThat(
            suppressor.retainActionable(
                List.of(component("pkg:maven/a/b@1", "CVE-1", "CVE-2"))))
        .hasSize(1);
  }

  @Test
  void retainsComponentsWhenAnAdvisoryIsWithdrawn() {
    when(repository.findByPurl("pkg:maven/a/b@1"))
        .thenReturn(Optional.of(record("pkg:maven/a/b@1", "pkg:maven/a/b@1|CVE-1,CVE-2")));

    assertThat(suppressor.retainActionable(List.of(component("pkg:maven/a/b@1", "CVE-1"))))
        .hasSize(1);
  }

  @Test
  void recordStoresTheJavaComputedFingerprintNotTheAgentsView() {
    ComponentFindings component = component("pkg:maven/a/b@1", "CVE-1", "CVE-9");
    UnfixableComponent unfixable =
        new UnfixableComponent("pkg:maven/a/b@1", List.of("CVE-1", "CVE-9"), "no fix available");

    suppressor.record(List.of(unfixable), List.of(component));

    verify(repository)
        .save(argThat(r -> r.fingerprint().equals("pkg:maven/a/b@1|CVE-1,CVE-9")));
  }

  @Test
  void recordStoresOneRowPerComponent() {
    ComponentFindings first = component("pkg:maven/a/b@1", "CVE-1");
    ComponentFindings second = component("pkg:maven/c/d@2", "CVE-2");
    UnfixableComponent firstUnfixable =
        new UnfixableComponent("pkg:maven/a/b@1", List.of("CVE-1"), "no fix available");
    UnfixableComponent secondUnfixable =
        new UnfixableComponent("pkg:maven/c/d@2", List.of("CVE-2"), "no fix available");

    suppressor.record(List.of(firstUnfixable, secondUnfixable), List.of(first, second));

    verify(repository, times(2)).save(any());
  }

  @Test
  void recordIgnoresPurlThatIsNotInTheCurrentFindings() {
    UnfixableComponent unfixable =
        new UnfixableComponent("pkg:maven/x/y@1", List.of("CVE-1"), "no fix available");

    suppressor.record(List.of(unfixable), List.of());

    verifyNoInteractions(repository);
  }
}
