package com.simonrowe.coparent.invitation;

import com.simonrowe.coparent.model.Invitation;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract for redacted invitation lifecycle operations. */
@RestController
@RequestMapping("/api/coparent")
public class InvitationController {

  private final InvitationService service;

  public InvitationController(final InvitationService service) {
    this.service = service;
  }

  @PostMapping("/families/{familyId}/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  InvitationResponse create(
      @PathVariable final String familyId,
      @Valid @RequestBody final CreateInvitationRequest request) {
    return InvitationResponse.from(service.create(
        CoparentIds.parse(familyId), request.email(), request.role()));
  }

  @GetMapping("/families/{familyId}/invitations")
  List<InvitationResponse> list(@PathVariable final String familyId) {
    return service.list(CoparentIds.parse(familyId)).stream()
        .map(InvitationResponse::from).toList();
  }

  @PostMapping("/invitations/{invitationId}/resend")
  InvitationResponse resend(@PathVariable final String invitationId) {
    return InvitationResponse.from(service.resend(CoparentIds.parse(invitationId)));
  }

  @PostMapping("/invitations/{invitationId}/cancel")
  InvitationResponse cancel(@PathVariable final String invitationId) {
    return InvitationResponse.from(service.cancel(CoparentIds.parse(invitationId)));
  }

  @PostMapping("/invitations/accept")
  AcceptanceResponse accept(@Valid @RequestBody final AcceptInvitationRequest request) {
    final InvitationService.Acceptance acceptance = service.accept(request.token());
    return new AcceptanceResponse("Invitation accepted successfully",
        InvitationResponse.from(acceptance.invitation()),
        new FamilySummary(acceptance.family().id().toHexString(), acceptance.family().name()));
  }

  record CreateInvitationRequest(@NotBlank @Email String email, @NotBlank String role) {
  }

  record AcceptInvitationRequest(@NotBlank String token) {
  }

  record FamilySummary(String id, String name) {
  }

  record AcceptanceResponse(
      String message,
      InvitationResponse invitation,
      FamilySummary family
  ) {
  }

  record InvitationResponse(
      String id,
      String familyId,
      String email,
      String role,
      String status,
      Instant sentAt,
      Instant expiresAt,
      Instant acceptedAt,
      Instant canceledAt
  ) {
    static InvitationResponse from(final Invitation invitation) {
      return new InvitationResponse(invitation.id().toHexString(),
          invitation.familyId().toHexString(), invitation.email(), invitation.role(),
          invitation.status(), invitation.sentAt(), invitation.expiresAt(),
          invitation.acceptedAt(), invitation.canceledAt());
    }
  }
}
