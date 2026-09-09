package com.simonrowe.school.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.simonrowe.school.retrieval.SchoolAudience;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Pins FR-027: every tool the school assistant exposes must be tier-aware.
 *
 * <p>The enforcement is structural rather than a naming convention. {@link SchoolTools} takes a
 * {@link SchoolAudience} in its only constructor, so a tool method cannot exist without one being
 * supplied — and this test fails if anybody adds a constructor that omits it, or moves a tool onto
 * a class with no audience at all.
 */
class SchoolToolsTierTest {

  @Test
  @DisplayName("every school tool lives on a class that requires an audience")
  void everyToolRequiresAnAudience() {
    final List<Method> tools = Arrays.stream(SchoolTools.class.getDeclaredMethods())
        .filter(m -> m.isAnnotationPresent(Tool.class))
        .toList();

    assertThat(tools)
        .as("SchoolTools should expose tools; if this is empty the reflection has drifted")
        .isNotEmpty();

    for (Constructor<?> constructor : SchoolTools.class.getConstructors()) {
      assertThat(constructor.getParameterTypes())
          .as("constructor %s must take a SchoolAudience", constructor)
          .contains(SchoolAudience.class);
    }
  }

  @Test
  @DisplayName("every tool carries a description a model can route on")
  void everyToolIsDescribed() {
    // A tool with no description is invisible to the model in practice: it will not be selected,
    // so the assistant silently loses a capability with nothing failing.
    Arrays.stream(SchoolTools.class.getDeclaredMethods())
        .filter(m -> m.isAnnotationPresent(Tool.class))
        .forEach(m -> assertThat(m.getAnnotation(Tool.class).description())
            .as("tool %s needs a description", m.getName())
            .isNotBlank());
  }

  @Test
  @DisplayName("the audience is not a tool parameter")
  void audienceIsNeverPassedAsToolParameter() {
    // If the tier were a tool argument the model could set it, and a prompt injection in a
    // retrieved newsletter could ask it to. It has to be constructor state.
    Arrays.stream(SchoolTools.class.getDeclaredMethods())
        .filter(m -> m.isAnnotationPresent(Tool.class))
        .forEach(m -> assertThat(m.getParameterTypes())
            .as("tool %s must not accept an audience", m.getName())
            .doesNotContain(SchoolAudience.class));
  }
}
