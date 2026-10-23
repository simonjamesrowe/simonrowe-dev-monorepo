package com.simonrowe.factory.staticanalysis.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.factory.testsupport.ComposeFile;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The module's flag belongs on {@code software-factory} and nowhere else.
 *
 * <p>Both halves matter. On the {@code deployer} the flag would register a second poller for the
 * queue on whatever build that container is running, which is how {@code logwatch-daily} wedged
 * for eleven days. Missing from {@code software-factory}, the module is off with no error
 * anywhere, and nothing files what pull requests are no longer checked for.
 */
class StaticAnalysisComposeTest {

  @Test
  void theFlagIsOnSoftwareFactory() throws IOException {
    List<String> factory = ComposeFile.serviceBlock(ComposeFile.lines(), "software-factory");

    assertThat(ComposeFile.declaredKeysContaining(factory, "STATICANALYSIS"))
        .contains("FACTORY_STATICANALYSIS_ENABLED");
  }

  @Test
  void theDeployerCarriesNone() throws IOException {
    List<String> deployer = ComposeFile.serviceBlock(ComposeFile.lines(), "deployer");

    assertThat(ComposeFile.declaredKeysContaining(deployer, "STATICANALYSIS")).isEmpty();
  }
}
