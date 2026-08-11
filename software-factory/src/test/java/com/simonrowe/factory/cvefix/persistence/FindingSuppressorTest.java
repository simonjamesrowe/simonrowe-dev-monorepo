package com.simonrowe.factory.cvefix.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.cvefix.domain.ComponentFindings;
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
}
