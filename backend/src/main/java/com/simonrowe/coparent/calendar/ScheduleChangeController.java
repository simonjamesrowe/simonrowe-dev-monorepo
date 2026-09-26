package com.simonrowe.coparent.calendar;

import com.simonrowe.coparent.model.ScheduleChangeRequest;
import com.simonrowe.coparent.shared.CoparentIds;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract for schedule-change requests and decisions. */
@RestController
@RequestMapping("/api/coparent/families/{familyId}/schedule-change-requests")
public class ScheduleChangeController {

  private final CalendarService service;
  private final ScheduleChangeDecisions decisions;

  public ScheduleChangeController(
      final CalendarService service, final ScheduleChangeDecisions decisions) {
    this.service = service;
    this.decisions = decisions;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ChangeResponse create(
      @PathVariable final String familyId,
      @RequestBody final CreateChangeRequest request) {
    return ChangeResponse.from(service.createChange(CoparentIds.parse(familyId),
        request.originalEventId() == null ? null : CoparentIds.parse(request.originalEventId()),
        request.proposedChange(), request.reason()));
  }

  @GetMapping
  List<ChangeResponse> list(@PathVariable final String familyId) {
    return service.listChanges(CoparentIds.parse(familyId)).stream()
        .map(ChangeResponse::from).toList();
  }

  @GetMapping("/{requestId}")
  ChangeResponse get(
      @PathVariable final String familyId,
      @PathVariable final String requestId) {
    return ChangeResponse.from(service.getChange(
        CoparentIds.parse(familyId), CoparentIds.parse(requestId)));
  }

  @PostMapping("/{requestId}/approve")
  ChangeResponse approve(
      @PathVariable final String familyId,
      @PathVariable final String requestId,
      @RequestBody(required = false) final DecisionRequest request) {
    return decide(familyId, requestId, "approved", request);
  }

  @PostMapping("/{requestId}/decline")
  ChangeResponse decline(
      @PathVariable final String familyId,
      @PathVariable final String requestId,
      @RequestBody(required = false) final DecisionRequest request) {
    return decide(familyId, requestId, "declined", request);
  }

  @DeleteMapping("/{requestId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void withdraw(
      @PathVariable final String familyId,
      @PathVariable final String requestId) {
    service.withdrawChange(CoparentIds.parse(familyId), CoparentIds.parse(requestId));
  }

  private ChangeResponse decide(
      final String familyId,
      final String requestId,
      final String decision,
      final DecisionRequest request) {
    return ChangeResponse.from(decisions.resolve(CoparentIds.parse(familyId),
        CoparentIds.parse(requestId), decision, request == null ? null : request.responseNote()));
  }

  record CreateChangeRequest(
      String originalEventId,
      ScheduleChangeRequest.ProposedChange proposedChange,
      String reason
  ) {
  }

  record DecisionRequest(String responseNote) {
  }

  record ChangeResponse(
      String id,
      String familyId,
      String status,
      String requestedBy,
      Instant requestedAt,
      String resolvedBy,
      Instant resolvedAt,
      String originalEventId,
      ScheduleChangeRequest.ProposedChange proposedChange,
      String reason,
      String responseNote
  ) {
    static ChangeResponse from(final ScheduleChangeRequest request) {
      return new ChangeResponse(request.id().toHexString(), request.familyId().toHexString(),
          request.status(), request.requestedBy().toHexString(), request.requestedAt(),
          hex(request.resolvedBy()), request.resolvedAt(), hex(request.originalEventId()),
          request.proposedChange(), request.reason(), request.responseNote());
    }

    private static String hex(final org.bson.types.ObjectId id) {
      return id == null ? null : id.toHexString();
    }
  }
}
