package com.simonrowe.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the privacy kill-switch against the real {@code application.yml}.
 *
 * <p>{@code LANGFUSE_CONTENT_CAPTURE_ENABLED} is documented in
 * {@code docs/runbooks/langfuse-observability.md} as the switch for content capture, so it must
 * turn off <em>all</em> capture. Two independent filters write content: our own
 * {@link LangfuseContentObservationFilter}, gated by {@code langfuse.content-capture-enabled}, and
 * Spring AI's {@code ToolCallingContentObservationFilter}, gated by
 * {@code spring.ai.tools.observations.include-content}. While the latter was hardcoded
 * {@code true}, setting the switch to {@code false} still left tool-call arguments — including
 * {@code ProfileMcpTools.submitContactForm}'s name, email, subject and message — flowing into
 * Langfuse as observation metadata.
 *
 * <p>Deliberately reads the shipped YAML rather than asserting on hand-written property values:
 * the defect was in the configuration file, so a test that supplies its own properties could not
 * have caught it. No Spring context and no Testcontainers, so this runs anywhere.
 */
class ContentCaptureKillSwitchTest {

  private static final String OURS = "langfuse.content-capture-enabled";
  private static final String SPRING_AI = "spring.ai.tools.observations.include-content";

  private static StandardEnvironment environmentWith(final Map<String, Object> overrides)
      throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    if (!overrides.isEmpty()) {
      environment.getPropertySources()
          .addFirst(new MapPropertySource("overrides", overrides));
    }
    List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"));
    for (PropertySource<?> source : loaded) {
      environment.getPropertySources().addLast(source);
    }
    return environment;
  }

  @Test
  void bothFiltersAreOnByDefault() throws IOException {
    StandardEnvironment environment = environmentWith(Map.of());

    assertThat(environment.getProperty(OURS, Boolean.class)).isTrue();
    assertThat(environment.getProperty(SPRING_AI, Boolean.class)).isTrue();
  }

  @Test
  void theDocumentedOffSwitchDisablesBothFilters() throws IOException {
    StandardEnvironment environment =
        environmentWith(Map.of("LANGFUSE_CONTENT_CAPTURE_ENABLED", "false"));

    assertThat(environment.getProperty(OURS, Boolean.class)).isFalse();
    assertThat(environment.getProperty(SPRING_AI, Boolean.class))
        .as("Spring AI's tool-call content filter must honour the same switch, or contact-form "
            + "arguments keep reaching Langfuse after capture is supposedly off")
        .isFalse();
  }

  @Test
  void bothFiltersReadTheSameEnvironmentVariable() throws IOException {
    StandardEnvironment on =
        environmentWith(Map.of("LANGFUSE_CONTENT_CAPTURE_ENABLED", "true"));

    assertThat(on.getProperty(OURS, Boolean.class))
        .isEqualTo(on.getProperty(SPRING_AI, Boolean.class));
  }
}
