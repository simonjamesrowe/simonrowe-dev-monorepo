package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the third-party log levels in the shipped {@code application.yml}.
 *
 * <p>Every entry under {@code logging.level} here exists to stop a library filing a log-watch
 * ticket that no change in this repository could ever close — PDFBox and FontBox for the fonts
 * the school PDFs do not embed (SIM-36, SIM-44, SIM-45), and Spring's
 * {@code BeanPostProcessorChecker} for beans that belong to Embabel, Spring AI and OpenTelemetry
 * (SIM-47).
 *
 * <p>The reason this is a test rather than a line of YAML taken on trust is the last of those.
 * Its logger name contains a {@code $}, and {@code logging.level} is bound as a map, so an
 * unbracketed key is adapted into a {@code ConfigurationPropertyName} with the {@code $} removed
 * — silently. The application starts, the level is applied to a logger no class owns, and the
 * WARNs keep coming. That is the same class of failure as every other silently-ignored property
 * in this repository, and the only way to see it is to bind the file the way Boot does and look
 * at the key that comes out.
 *
 * <p>No Spring context and no Testcontainers, so this runs anywhere.
 */
class LoggingLevelConfigTest {

  private static final String CHECKER =
      "org.springframework.context.support.PostProcessorRegistrationDelegate"
          + "$BeanPostProcessorChecker";

  private static Map<String, String> boundLevels() throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    List<PropertySource<?>> loaded =
        new YamlPropertySourceLoader()
            .load("application.yml", new ClassPathResource("application.yml"));
    for (PropertySource<?> source : loaded) {
      environment.getPropertySources().addLast(source);
    }
    return Binder.get(environment)
        .bind("logging.level", Bindable.mapOf(String.class, String.class))
        .orElse(Map.of());
  }

  @Test
  @DisplayName("the logger names bind exactly as written, dollar sign included")
  void bindsTheLoggerNamesVerbatim() throws IOException {
    Map<String, String> levels = boundLevels();

    assertThat(levels)
        .as("the BeanPostProcessorChecker key lost its $ while binding, so it silences nothing")
        .containsEntry(CHECKER, "ERROR");
    assertThat(levels)
        .containsEntry("org.apache.pdfbox.pdmodel.font", "ERROR")
        .containsEntry("org.apache.fontbox.ttf", "ERROR");
  }

  /**
   * Each of these is scoped one level below the obvious package on purpose: a document PDFBox
   * cannot parse, a font FontBox cannot read at all, and any other warning Spring's context
   * support emits are all still reported. Silencing the parent package would take those with it.
   */
  @Test
  @DisplayName("nothing is silenced more broadly than the noise it was written for")
  void silencesNoParentPackage() throws IOException {
    Map<String, String> levels = boundLevels();

    assertThat(levels).doesNotContainKeys(
        "org.apache.pdfbox",
        "org.apache.fontbox",
        "org.springframework.context.support",
        "org.springframework");
  }
}
