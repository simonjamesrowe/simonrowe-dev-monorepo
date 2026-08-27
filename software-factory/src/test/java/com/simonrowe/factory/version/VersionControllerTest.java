package com.simonrowe.factory.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class VersionControllerTest {

  private static BuildProperties buildProperties() {
    Properties properties = new Properties();
    properties.put("commit", "840c311abcdef0123456789abcdef0123456789a");
    properties.put("commitTime", "1756200000");
    properties.put("commitSubject", "feat: deploy automatically");
    return new BuildProperties(properties);
  }

  @Test
  void reportsTheBakedCommit() {
    FactoryVersion version = new VersionController(buildProperties()).version();

    assertThat(version.commit()).isEqualTo("840c311abcdef0123456789abcdef0123456789a");
    assertThat(version.shortCommit()).isEqualTo("840c311");
    assertThat(version.commitSubject()).isEqualTo("feat: deploy automatically");
    assertThat(version.commitTime()).isEqualTo(Instant.ofEpochSecond(1756200000L));
  }

  @Test
  void reportsDevBuildWhenNoBuildInfoIsPresent() {
    FactoryVersion version = new VersionController(null).version();

    assertThat(version.commit()).isEqualTo("unknown");
    assertThat(version.shortCommit()).isEqualTo("dev");
    assertThat(version.commitTime()).isNull();
  }

  @Test
  void startedAtIsStableAcrossCalls() {
    VersionController controller = new VersionController(buildProperties());

    assertThat(controller.version().startedAt()).isEqualTo(controller.version().startedAt());
  }

  @Test
  void treatsAnEpochCommitTimeAsUnknown() {
    Properties properties = new Properties();
    properties.put("commit", "840c311abcdef0123456789abcdef0123456789a");
    properties.put("commitTime", "0");

    assertThat(new VersionController(new BuildProperties(properties)).version().commitTime())
        .isNull();
  }
}
