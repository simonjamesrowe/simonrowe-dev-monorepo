package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatConfigPromptTest {

  @Test
  void widgetPromptGuidanceIncludesVisualWidgetAndLinkRules() {
    String guidance = ChatConfig.widgetPromptGuidance();

    assertThat(guidance).contains("blog", "news", "event");
    assertThat(guidance).contains("visual card");
    assertThat(guidance).contains("do not re-list");
    // Content questions must call the tool so the card renders, not answer from RAG context.
    assertThat(guidance).contains("ALWAYS call the matching tool");
    assertThat(guidance).contains("no card");
    // Band-aid removed (US1): the model is no longer told about answer lifecycle.
    assertThat(guidance).doesNotContain("unless the visitor has sent a new prompt");
    // Rich, safe link/image guidance (US3).
    assertThat(guidance).contains("/blogs/");
    assertThat(guidance).contains("/about?job=");
    assertThat(guidance).contains("/about?skillGroup=");
    assertThat(guidance).contains("/about#roles");
    // Profile and Experience merged into /about; the retired paths only redirect now.
    assertThat(guidance).doesNotContain("/experience");
    assertThat(guidance).doesNotContain("(/profile");
    assertThat(guidance).contains("Never invent");
  }
}
