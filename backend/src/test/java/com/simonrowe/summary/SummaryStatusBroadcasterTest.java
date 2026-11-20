package com.simonrowe.summary;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class SummaryStatusBroadcasterTest {

  @Mock private SimpMessagingTemplate messagingTemplate;

  @Test
  void sendsTheArticleAndStateToThePublicSummariesTopic() {
    new SummaryStatusBroadcaster(messagingTemplate)
        .broadcast("art-1", ArticleSummaryResponse.PublicState.READY);

    verify(messagingTemplate).convertAndSend(
        "/topic/summaries",
        new SummaryStatusUpdate("art-1", ArticleSummaryResponse.PublicState.READY));
  }

  @Test
  void failedSendIsSwallowedSoWrittenSummaryIsNotReportedAsFailed() {
    doThrow(new MessageDeliveryException("broker down"))
        .when(messagingTemplate).convertAndSend(eq("/topic/summaries"), any(Object.class));

    assertThatCode(() -> new SummaryStatusBroadcaster(messagingTemplate)
        .broadcast("art-1", ArticleSummaryResponse.PublicState.READY))
        .doesNotThrowAnyException();
  }
}
