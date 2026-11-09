package com.simonrowe.factory.deploy.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.deploy.domain.PlanFacts;
import com.simonrowe.factory.deploy.shell.PhaseRunner;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reading {@code restart-prod.sh plan}'s output.
 *
 * <p>The misreading that matters is a missing or failed fact read as "nothing changed": the
 * workflow would then report the commit as deployed and touch nothing.
 */
class PlanFactsParsingTest {

  private static Map<String, String> complete() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("image-changed", "frontend page-reader");
    values.put("config-changed", "");
    values.put("config-read", "ok");
    values.put("host-config-changed", "");
    values.put("host-config-read", "skipped");
    return values;
  }

  private static PlanFacts parse(final int exitCode, final Map<String, String> values) {
    return DeployActivitiesImpl.planFactsFrom(
        new PhaseRunner.PhaseExecution(exitCode, "", values, 10L));
  }

  @Test
  void readsTheChangedServices() {
    PlanFacts facts = parse(0, complete());

    assertThat(facts.readable()).isTrue();
    assertThat(facts.changedImages()).containsExactly("frontend", "page-reader");
    assertThat(facts.changedConfig()).isEmpty();
    assertThat(facts.changedHostFiles()).isEmpty();
  }

  @Test
  void nonZeroExitIsUnreadable() {
    assertThat(parse(1, complete()).readable()).isFalse();
  }

  @Test
  void missingFactIsUnreadableRatherThanUnchanged() {
    for (String key : new String[] {"image-changed", "config-changed", "host-config-changed"}) {
      Map<String, String> values = complete();
      values.remove(key);
      assertThat(parse(0, values).readable()).as(key).isFalse();
    }
  }

  @Test
  void failedHashReadIsUnreadable() {
    Map<String, String> values = complete();
    values.put("config-read", "failed");

    assertThat(parse(0, values).readable()).isFalse();
  }

  @Test
  void failedConfigDiffIsUnreadable() {
    Map<String, String> values = complete();
    values.put("host-config-read", "failed");

    assertThat(parse(0, values).readable()).isFalse();
  }

  @Test
  void readsChangedHostFiles() {
    Map<String, String> values = complete();
    values.put("host-config-read", "ok");
    values.put("host-config-changed", "config/nginx/nginx-proxy.conf config/alloy/config.alloy");

    assertThat(parse(0, values).changedHostFiles())
        .containsExactly("config/nginx/nginx-proxy.conf", "config/alloy/config.alloy");
  }
}
