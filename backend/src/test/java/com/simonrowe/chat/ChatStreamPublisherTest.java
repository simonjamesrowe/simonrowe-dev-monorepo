package com.simonrowe.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class ChatStreamPublisherTest {

  @Mock
  private SimpMessagingTemplate messagingTemplate;

  @Test
  void publishesToolStartToSessionTopic() {
    ChatStreamPublisher publisher = new ChatStreamPublisher(messagingTemplate);

    publisher.toolStart("session-1", "Looking up Simon's skills");

    ArgumentCaptor<ChatResponse> captor = ArgumentCaptor.forClass(ChatResponse.class);
    verify(messagingTemplate).convertAndSend(eq("/topic/chat.session-1"), captor.capture());

    assertThat(captor.getValue().type()).isEqualTo(ChatResponse.MessageType.TOOL_START);
    assertThat(captor.getValue().toolLabel()).isEqualTo("Looking up Simon's skills");
  }

  @Test
  void publishesWidgetToSessionTopic() {
    ChatStreamPublisher publisher = new ChatStreamPublisher(messagingTemplate);
    Map<String, Object> payload = Map.of("posts", java.util.List.of());

    publisher.widget("session-2", "blogs", payload);

    ArgumentCaptor<ChatResponse> captor = ArgumentCaptor.forClass(ChatResponse.class);
    verify(messagingTemplate).convertAndSend(eq("/topic/chat.session-2"), captor.capture());

    assertThat(captor.getValue().type()).isEqualTo(ChatResponse.MessageType.WIDGET);
    assertThat(captor.getValue().widgetKind()).isEqualTo("blogs");
    assertThat(captor.getValue().payload()).isSameAs(payload);
  }
}
