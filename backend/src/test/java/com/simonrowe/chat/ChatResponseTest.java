package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatResponseTest {

  @Test
  void toolStartContainsLabelAndNoContent() {
    ChatResponse response = ChatResponse.toolStart(
        "session-1", "Looking up Simon's skills");

    assertThat(response.sessionId()).isEqualTo("session-1");
    assertThat(response.type()).isEqualTo(ChatResponse.MessageType.TOOL_START);
    assertThat(response.content()).isEmpty();
    assertThat(response.toolLabel()).isEqualTo("Looking up Simon's skills");
    assertThat(response.widgetKind()).isNull();
    assertThat(response.payload()).isNull();
    assertThat(response.timestamp()).isNotBlank();
  }

  @Test
  void widgetContainsKindAndPayload() {
    Map<String, Object> payload = Map.of("groups", java.util.List.of());

    ChatResponse response = ChatResponse.widget("session-2", "skills", payload);

    assertThat(response.sessionId()).isEqualTo("session-2");
    assertThat(response.type()).isEqualTo(ChatResponse.MessageType.WIDGET);
    assertThat(response.content()).isEmpty();
    assertThat(response.toolLabel()).isNull();
    assertThat(response.widgetKind()).isEqualTo("skills");
    assertThat(response.payload()).isSameAs(payload);
  }

  @Test
  void streamResetIsNotAnAvailableMessageType() {
    assertThat(ChatResponse.MessageType.values())
        .extracting(Enum::name)
        .doesNotContain("STREAM_" + "RESET");
  }
}
