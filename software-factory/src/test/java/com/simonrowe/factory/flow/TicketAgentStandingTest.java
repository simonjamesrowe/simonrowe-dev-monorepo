package com.simonrowe.factory.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TicketAgentStandingTest {

  private final LinearGateway linear = mock(LinearGateway.class);
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
  private final Clock clock = new Clock() {
    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  };

  private static LinearProperties linear(final boolean enabled) {
    return new LinearProperties(enabled, "k", null, "SIM", null, false, null, null);
  }

  private static LinearTicket ticket(final String identifier) {
    return new LinearTicket("id-" + identifier, identifier, "t", "", "u", IssueStateType.TRIAGE,
        "Triage", List.of(), 0, Instant.EPOCH);
  }

  private TicketAgentStanding standing(final boolean agentOn, final boolean linearOn) {
    return new TicketAgentStanding(
        linear, linear(linearOn), TicketAgentTestProperties.of(agentOn), clock);
  }

  @Test
  void marksHeldAndStuckTicketsWithHeldWinning() {
    when(linear.openTicketsLabelled("agent:stuck"))
        .thenReturn(List.of(ticket("SIM-3"), ticket("SIM-2")));
    when(linear.openTicketsLabelled("factory:agent")).thenReturn(List.of(ticket("SIM-2")));

    assertThat(standing(true, true).byIdentifier())
        .containsEntry("SIM-3", FlowDetail.Item.STUCK)
        .containsEntry("SIM-2", FlowDetail.Item.WITH_AGENT)
        .hasSize(2);
  }

  /** The drawer refreshes every 15s; an uncached read would spend Linear's hourly allowance. */
  @Test
  void readsLinearAtMostOnceMinute() {
    TicketAgentStanding standing = standing(true, true);
    when(linear.openTicketsLabelled("factory:agent")).thenReturn(List.of());
    when(linear.openTicketsLabelled("agent:stuck")).thenReturn(List.of());

    standing.byIdentifier();
    now.set(Instant.EPOCH.plusSeconds(59));
    standing.byIdentifier();
    now.set(Instant.EPOCH.plusSeconds(61));
    standing.byIdentifier();

    verify(linear, times(2)).openTicketsLabelled("factory:agent");
  }

  @Test
  void failedReadIsEmptyAndNotCached() {
    TicketAgentStanding standing = standing(true, true);
    when(linear.openTicketsLabelled("agent:stuck"))
        .thenThrow(new LinearApiException("503", true))
        .thenReturn(List.of(ticket("SIM-9")));
    when(linear.openTicketsLabelled("factory:agent")).thenReturn(List.of());

    assertThat(standing.byIdentifier()).isEmpty();
    assertThat(standing.byIdentifier()).containsKey("SIM-9");
  }

  @Test
  void asksNothingWhileTheAgentOrLinearIsOff() {
    assertThat(standing(false, true).byIdentifier()).isEmpty();
    assertThat(standing(true, false).byIdentifier()).isEmpty();
    verifyNoInteractions(linear);
  }
}
