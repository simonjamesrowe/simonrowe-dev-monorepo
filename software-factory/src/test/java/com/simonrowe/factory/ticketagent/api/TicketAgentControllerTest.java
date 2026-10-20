package com.simonrowe.factory.ticketagent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.codereview.config.CodeReviewProperties;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.IssueStateType;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.testsupport.TicketAgentTestProperties;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TicketAgentControllerTest {

  private static final String TOKEN = "trigger-token";

  private final LinearGateway linear = mock(LinearGateway.class);
  private final TicketAgentService service = mock(TicketAgentService.class);

  private MockMvc mockMvc(final boolean agentOn, final boolean linearOn) {
    return MockMvcBuilders.standaloneSetup(
            new TicketAgentController(
                new FactoryTokenAuthenticator(
                    new CodeReviewProperties(
                        null, null, new CodeReviewProperties.Api(TOKEN, null), null)),
                TicketAgentTestProperties.of(agentOn),
                new LinearProperties(linearOn, "k", null, "SIM", null, false, null, null),
                linear, service))
        .build();
  }

  private static LinearTicket ticket(final IssueStateType state, final String... labels) {
    return new LinearTicket("i1", "SIM-107", "t", "", "u", state, "Triage", List.of(labels), 2,
        Instant.EPOCH);
  }

  private ResultActions start(final MockMvc mvc, final String body) throws Exception {
    return mvc.perform(post("/api/ticket-agent/runs").header("X-Factory-Token", TOKEN)
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void handingOverAddsTheLabelClearsStuckAndStarts() throws Exception {
    when(linear.ticket("SIM-107")).thenReturn(ticket(IssueStateType.UNSTARTED, "agent:stuck"));
    when(service.start(any())).thenReturn(new TicketAgentAccepted("ticket-agent-SIM-107", true));

    start(mockMvc(true, true), "{\"ticket\":\"https://linear.app/x/issue/SIM-107/slug\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("ticket-agent-SIM-107"))
        .andExpect(jsonPath("$.detail").value("Handed SIM-107 to the agent"));

    verify(linear).addLabel("i1", "factory:agent");
    verify(linear).removeLabel("i1", "agent:stuck");
    verify(service).start(new TicketAgentRequest("SIM-107", false, "console"));
  }

  @Test
  void dryRunNeedsNoLabelAndAddsNone() throws Exception {
    when(linear.ticket("SIM-107")).thenReturn(ticket(IssueStateType.TRIAGE));
    when(service.start(any()))
        .thenReturn(new TicketAgentAccepted("ticket-agent-dry-run-SIM-107", true));

    start(mockMvc(true, true), "{\"ticket\":\"sim-107\",\"dryRun\":true}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.detail").value("Dry-running SIM-107"));

    verify(linear, never()).addLabel(any(), any());
  }

  @Test
  void refusesWhatIsNotTicket() throws Exception {
    start(mockMvc(true, true), "{\"ticket\":\"https://linear.app.evil.example/issue/SIM-1\"}")
        .andExpect(status().isBadRequest());
    start(mockMvc(true, true), "{}").andExpect(status().isBadRequest());
  }

  @Test
  void unknownTicketIsNotFoundAndClosedOneConflicts() throws Exception {
    when(linear.ticket("SIM-404")).thenReturn(null);
    when(linear.ticket("SIM-107")).thenReturn(ticket(IssueStateType.COMPLETED));

    start(mockMvc(true, true), "{\"ticket\":\"SIM-404\"}").andExpect(status().isNotFound());
    start(mockMvc(true, true), "{\"ticket\":\"SIM-107\"}").andExpect(status().isConflict());
    verify(service, never()).start(any());
  }

  @Test
  void liveRunConflicts() throws Exception {
    when(linear.ticket("SIM-107")).thenReturn(ticket(IssueStateType.TRIAGE, "factory:agent"));
    when(service.start(any())).thenReturn(new TicketAgentAccepted("ticket-agent-SIM-107", false));

    start(mockMvc(true, true), "{\"ticket\":\"SIM-107\"}").andExpect(status().isConflict());
    verify(linear, never()).addLabel(any(), any());
  }

  @Test
  void linearFaultsAreBadGateway() throws Exception {
    when(linear.ticket("SIM-1")).thenThrow(new LinearApiException("down", true));
    when(linear.ticket("SIM-2")).thenReturn(ticket(IssueStateType.TRIAGE));
    doThrow(new LinearApiException("no", false))
        .when(linear).addLabel("i1", "factory:agent");

    start(mockMvc(true, true), "{\"ticket\":\"SIM-1\"}").andExpect(status().isBadGateway());
    start(mockMvc(true, true), "{\"ticket\":\"SIM-2\"}").andExpect(status().isBadGateway());
  }

  @Test
  void isUnavailableWhileTheAgentOrLinearIsOff() throws Exception {
    start(mockMvc(false, true), "{\"ticket\":\"SIM-107\"}")
        .andExpect(status().isServiceUnavailable());
    start(mockMvc(true, false), "{\"ticket\":\"SIM-107\"}")
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void needsTheTriggerToken() throws Exception {
    mockMvc(true, true).perform(post("/api/ticket-agent/runs")
            .contentType(MediaType.APPLICATION_JSON).content("{\"ticket\":\"SIM-1\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void stopsLiveRunAndSaysWhenThereIsNone() throws Exception {
    when(service.stop("SIM-107", "Stopped from the software factory console")).thenReturn(true);
    MockMvc mvc = mockMvc(true, true);

    mvc.perform(post("/api/ticket-agent/runs/sim-107/stop").header("X-Factory-Token", TOKEN))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.workflowId").value("ticket-agent-SIM-107"));
    mvc.perform(post("/api/ticket-agent/runs/SIM-9/stop").header("X-Factory-Token", TOKEN))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/ticket-agent/runs/nope/stop").header("X-Factory-Token", TOKEN))
        .andExpect(status().isBadRequest());
  }

  @Test
  void pausesAndResumesTheIntake() throws Exception {
    MockMvc mvc = mockMvc(true, true);

    mvc.perform(post("/api/ticket-agent/intake").header("X-Factory-Token", TOKEN)
            .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.detail").value("Ticket intake paused"));
    mvc.perform(post("/api/ticket-agent/intake").header("X-Factory-Token", TOKEN)
            .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":false}"))
        .andExpect(jsonPath("$.detail").value("Ticket intake resumed"));

    verify(service).setIntakePaused(true);
    verify(service).setIntakePaused(false);
    verify(service, never()).start(any());
    verifyNoMoreInteractions(linear);
  }
}
