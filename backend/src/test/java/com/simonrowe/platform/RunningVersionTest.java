package com.simonrowe.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class RunningVersionTest {

  private static BuildProperties buildProperties(final String commit, final String subject) {
    Properties properties = new Properties();
    properties.put("group", "com.simonrowe");
    properties.put("artifact", "backend");
    properties.put("version", "0.0.1-SNAPSHOT");
    properties.put("time", "1756200000");
    if (commit != null) {
      properties.put("commit", commit);
      properties.put("commitTime", "1756200000");
      properties.put("commitSubject", subject);
    }
    return new BuildProperties(properties);
  }

  @Test
  void reportsTheBakedCommit() {
    RunningVersion version = new RunningVersion(
        buildProperties("840c311abcdef0123456789abcdef0123456789a", "docs: overhaul the README"));

    ServiceVersion current = version.current();

    assertThat(current.name()).isEqualTo("backend");
    assertThat(current.commit()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(current.shortCommit()).isEqualTo("840c311");
    assertThat(current.commitSubject()).isEqualTo("docs: overhaul the README");
    assertThat(current.commitTime()).isEqualTo(Instant.ofEpochSecond(1756200000L));
    assertThat(current.reachable()).isTrue();
  }

  @Test
  void reportsDevBuildWhenNoBuildInfoIsPresent() {
    RunningVersion version = new RunningVersion(null);

    ServiceVersion current = version.current();

    assertThat(current.commit()).isEqualTo("unknown");
    assertThat(current.shortCommit()).isEqualTo("dev");
    assertThat(current.commitTime()).isNull();
    assertThat(current.reachable()).isTrue();
  }

  @Test
  void treatsAnEpochCommitTimeAsUnknown() {
    Properties properties = new Properties();
    properties.put("commit", "840c311abcdef0123456789abcdef0123456789a");
    properties.put("commitTime", "0");
    RunningVersion version = new RunningVersion(new BuildProperties(properties));

    assertThat(version.current().commitTime()).isNull();
  }

  @Test
  void startedAtIsSetOnConstruction() {
    Instant before = Instant.now();
    RunningVersion version = new RunningVersion(null);

    assertThat(version.startedAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
  }
}
