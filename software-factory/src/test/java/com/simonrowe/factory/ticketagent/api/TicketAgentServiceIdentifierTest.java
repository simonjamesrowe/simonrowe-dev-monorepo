package com.simonrowe.factory.ticketagent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class TicketAgentServiceIdentifierTest {

  private final TicketAgentService service =
      new TicketAgentService(
          mock(WorkflowClient.class), mock(ScheduleClient.class),
          new TicketAgentProperties(
              true, null, null, null, null, null, 0, 0, null, null, null, null, null, null,
              null, null, null, null, 0, null, null));

  @Test
  void identifiersAreLettersDashDigits() {
    for (String valid : List.of("SIM-1", "SIM-107", "AB2-9")) {
      assertThat(TicketAgentService.isIdentifier(valid)).as(valid).isTrue();
    }
    for (String invalid :
        List.of("", "SIM", "SIM-", "-1", "sim-1", "SIM-1-2", "SIM-1a", "2SIM-1",
            "SIM-1; drop", "S".repeat(40) + "-1")) {
      assertThat(TicketAgentService.isIdentifier(invalid)).as(invalid).isFalse();
    }
    assertThat(TicketAgentService.isIdentifier(null)).isFalse();
  }

  @Test
  void anAgentBranchNamesItsTicket() {
    assertThat(service.branchFor("SIM-107")).isEqualTo("agent/sim-107");
    assertThat(service.identifierFromBranch("agent/sim-107")).contains("SIM-107");
  }

  @Test
  void otherBranchesNameNoTicket() {
    for (String branch :
        List.of("main", "feature/sim-107", "agent/", "agent/sim-107/extra", "agent/../x")) {
      assertThat(service.identifierFromBranch(branch)).as(branch).isEmpty();
    }
    assertThat(service.identifierFromBranch(null)).isEmpty();
  }

  @Test
  void dryRunsHaveTheirOwnWorkflowId() {
    assertThat(TicketAgentService.workflowId("SIM-1", false)).isEqualTo("ticket-agent-SIM-1");
    assertThat(TicketAgentService.workflowId("SIM-1", true))
        .isEqualTo("ticket-agent-dry-run-SIM-1");
  }

  @Test
  void ticketReferencesAcceptIdentifiersAndLinearUrlsOnly() {
    assertThat(TicketReference.identifier("SIM-107")).contains("SIM-107");
    assertThat(TicketReference.identifier(" sim-107 ")).contains("SIM-107");
    assertThat(
            TicketReference.identifier(
                "https://linear.app/simonrowedev/issue/SIM-107/error-in-backend"))
        .contains("SIM-107");
    for (String bad :
        List.of(
            "https://linear.app.attacker.example/x/issue/SIM-1",
            "http://linear.app/x/issue/SIM-1",
            "https://linear.app/x/project/SIM-1",
            "https://github.com/o/r/pull/1",
            "nonsense")) {
      assertThat(TicketReference.identifier(bad)).as(bad).isEmpty();
    }
  }
}
