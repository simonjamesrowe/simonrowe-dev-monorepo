package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatConfigPromptTest {

  @Test
  void widgetPromptGuidanceIncludesVisualWidgetAndPromptLifecycleRules() {
    String guidance = ChatConfig.widgetPromptGuidance();

    assertThat(guidance).contains("blog", "news", "event");
    assertThat(guidance).contains("visual card");
    assertThat(guidance).contains("do not re-list");
    assertThat(guidance).contains("unless the visitor has sent a new prompt");
  }
}
