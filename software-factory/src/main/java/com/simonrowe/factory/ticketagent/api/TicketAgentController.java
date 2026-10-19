package com.simonrowe.factory.ticketagent.api;

import com.simonrowe.factory.admin.FactoryTokenAuthenticator;
import com.simonrowe.factory.linear.config.LinearProperties;
import com.simonrowe.factory.linear.domain.LinearTicket;
import com.simonrowe.factory.linear.linear.LinearApiException;
import com.simonrowe.factory.linear.linear.LinearGateway;
import com.simonrowe.factory.ticketagent.config.TicketAgentProperties;
import com.simonrowe.factory.ticketagent.domain.TicketAgentRequest;
import com.simonrowe.factory.ticketagent.schedule.TicketAgentScheduleInitializer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Token-protected console API for the ticket agent; never routed by nginx.
 *
 * <p>Handing a ticket over from the console applies the {@code factory:agent} label in Linear
 * before starting the run, rather than starting a run with no label. The label stays the one
 * record of what a person approved, whichever door the approval came through, and the intake
 * already treats a labelled ticket as approved.
 */
@RestController
@RequestMapping("/api/ticket-agent")
public class TicketAgentController {

  private final FactoryTokenAuthenticator authenticator;
  private final TicketAgentProperties properties;
  private final LinearProperties linearProperties;
  private final LinearGateway linear;
  private final TicketAgentService service;

  public TicketAgentController(
      final FactoryTokenAuthenticator authenticator,
      final TicketAgentProperties properties,
      final LinearProperties linearProperties,
      final LinearGateway linear,
      final TicketAgentService service) {
    this.authenticator = authenticator;
    this.properties = properties;
    this.linearProperties = linearProperties;
    this.linear = linear;
    this.service = service;
  }

  /**
   * Hands a ticket to the agent, or dry-runs it.
   *
   * @return {@code 202} when a run started, {@code 409} when one was already live
   */
  @PostMapping("/runs")
  public ResponseEntity<TicketRunAccepted> start(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @RequestBody(required = false) final TicketRunRequest request) {
    authenticator.authenticate(token);
    requireEnabled();
    String identifier =
        TicketReference.identifier(request == null ? null : request.ticket())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Give a ticket as SIM-123 or its Linear URL"));
    boolean dryRun = request.isDryRun();
    LinearTicket ticket = read(identifier);
    if (!ticket.stateType().open()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, identifier + " is closed, so there is nothing to work on");
    }
    if (!dryRun) {
      approve(ticket);
    }
    TicketAgentAccepted accepted =
        service.start(
            new TicketAgentRequest(identifier, dryRun, TicketAgentRequest.TRIGGER_CONSOLE));
    if (!accepted.started()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The agent is already working on " + identifier);
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new TicketRunAccepted(
            accepted.workflowId(), null,
            (dryRun ? "Dry-running " : "Handed ") + identifier + (dryRun ? "" : " to the agent")));
  }

  /**
   * Stops a live run; the ticket is handed back and the pull request left as a draft.
   *
   * @return {@code 202}, or {@code 404} when the ticket has no live run
   */
  @PostMapping("/runs/{identifier}/stop")
  public ResponseEntity<TicketRunAccepted> stop(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @PathVariable final String identifier) {
    authenticator.authenticate(token);
    requireEnabled();
    String normalised =
        TicketReference.identifier(identifier)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a ticket"));
    if (!service.stop(normalised, "Stopped from the software factory console")) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "The agent is not working on " + normalised);
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new TicketRunAccepted(
            "ticket-agent-" + normalised, null, "Stopping " + normalised));
  }

  /** Pauses or resumes the intake schedule. */
  @PostMapping("/intake")
  public ResponseEntity<TicketRunAccepted> intake(
      @RequestHeader(value = "X-Factory-Token", required = false) final String token,
      @RequestBody final IntakeRequest request) {
    authenticator.authenticate(token);
    requireEnabled();
    service.setIntakePaused(request.paused());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(new TicketRunAccepted(
            TicketAgentScheduleInitializer.SCHEDULE_ID, null,
            request.paused() ? "Ticket intake paused" : "Ticket intake resumed"));
  }

  /** The label is the approval; a stuck label from an earlier hand-back is cleared with it. */
  private void approve(final LinearTicket ticket) {
    try {
      if (!ticket.hasLabel(properties.label())) {
        linear.addLabel(ticket.id(), properties.label());
      }
      if (ticket.hasLabel(properties.stuckLabel())) {
        linear.removeLabel(ticket.id(), properties.stuckLabel());
      }
    } catch (LinearApiException exception) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Linear refused the label: " + exception.getMessage());
    }
  }

  private LinearTicket read(final String identifier) {
    LinearTicket ticket;
    try {
      ticket = linear.ticket(identifier);
    } catch (LinearApiException exception) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Linear could not be read: " + exception.getMessage());
    }
    if (ticket == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Linear has no ticket " + identifier);
    }
    return ticket;
  }

  private void requireEnabled() {
    if (!properties.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "The ticket agent is disabled");
    }
    if (!linearProperties.enabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Linear is disabled, so the ticket agent cannot work");
    }
  }
}
