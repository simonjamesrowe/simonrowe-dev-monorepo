package com.simonrowe.factory.ticketagent.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simonrowe.factory.git.RepositoryWorkspaceFactory;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import com.simonrowe.factory.ticketagent.agent.TicketAgentEngine;
import com.simonrowe.factory.ticketagent.api.TicketAgentAccepted;
import com.simonrowe.factory.ticketagent.api.TicketAgentService;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import com.simonrowe.factory.ticketagent.github.TicketAgentGitHub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TicketAgentDispatchTest {

  private final LinearGateway linear = mock(LinearGateway.class);
  private final TicketAgentService service = mock(TicketAgentService.class);
  private final List<String> started = new ArrayList<>();
  private final TicketAgentActivitiesImpl activities =
      new TicketAgentActivitiesImpl(
          TicketAgentTestProperties.of(true), linear, mock(TicketAgentGitHub.class),
          mock(TicketAgentEngine.class), mock(RepositoryWorkspaceFactory.class), service);

  private static LinearTicket ticket(
      final String identifier, final String state, final int priority, final long created,
      final String... labels) {
    List<String> all = new ArrayList<>(List.of("factory:agent"));
    all.addAll(List.of(labels));
    return new LinearTicket(
        "id-" + identifier, identifier, "t", "", "u", IssueStateType.UNSTARTED, state, all,
        priority, Instant.ofEpochSecond(created));
  }

  private void running(final Set<String> live) {
    when(service.start(any())).thenAnswer(invocation -> {
      TicketAgentRequest request = invocation.getArgument(0);
      boolean fresh = !live.contains(request.identifier());
      if (fresh) {
        started.add(request.identifier());
      }
      return new TicketAgentAccepted("ticket-agent-" + request.identifier(), fresh);
    });
  }

  @Test
  void startsTheMostUrgentOldestTicketUpToTheCap() {
    when(linear.openTicketsLabelled("factory:agent")).thenReturn(List.of(
        ticket("SIM-3", "Todo", 3, 30),
        ticket("SIM-2", "Triage", 1, 20),
        ticket("SIM-1", "Todo", 1, 10),
        ticket("SIM-9", "Todo", 0, 1)));
    running(Set.of());

    assertThat(activities.dispatch()).isEqualTo(1);
    assertThat(started).containsExactly("SIM-1");
  }

  @Test
  void liveClaimedTicketUsesTheOnlySlot() {
    when(linear.openTicketsLabelled("factory:agent")).thenReturn(List.of(
        ticket("SIM-5", "In Progress", 2, 5), ticket("SIM-1", "Todo", 1, 1)));
    running(Set.of("SIM-5"));

    assertThat(activities.dispatch()).isZero();
    assertThat(started).isEmpty();
  }

  /** A claim whose run crashed before it could hand back is restarted, not left blocking. */
  @Test
  void claimedTicketWithNoLiveRunIsRestarted() {
    when(linear.openTicketsLabelled("factory:agent")).thenReturn(List.of(
        ticket("SIM-5", "In Progress", 2, 5), ticket("SIM-1", "Todo", 1, 1)));
    running(Set.of());

    assertThat(activities.dispatch()).isEqualTo(1);
    assertThat(started).containsExactly("SIM-5");
  }

  @Test
  void parkedAndStuckTicketsAreLeftAlone() {
    when(linear.openTicketsLabelled("factory:agent")).thenReturn(List.of(
        ticket("SIM-4", "In Review", 1, 4),
        ticket("SIM-6", "Todo", 1, 6, "agent:stuck"),
        ticket("SIM-7", "Todo", 2, 7)));
    running(Set.of());

    activities.dispatch();

    assertThat(started).containsExactly("SIM-7");
  }

  @Test
  void priorityNoneSortsAfterLow() {
    assertThat(
            List.of(ticket("SIM-1", "Todo", 0, 1), ticket("SIM-2", "Todo", 4, 2)).stream()
                .sorted(TicketAgentActivitiesImpl.INTAKE_ORDER)
                .map(LinearTicket::identifier))
        .containsExactly("SIM-2", "SIM-1");
  }
}
