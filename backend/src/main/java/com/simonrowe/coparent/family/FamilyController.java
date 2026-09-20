package com.simonrowe.coparent.family;

import com.simonrowe.coparent.model.Family;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract for current-user, family and parent membership operations. */
@RestController
@RequestMapping("/api/coparent")
public class FamilyController {

  private final FamilyService service;

  public FamilyController(final FamilyService service) {
    this.service = service;
  }

  @GetMapping("/me")
  CurrentUserResponse currentUser() {
    return CurrentUserResponse.from(service.currentUser());
  }

  @PostMapping("/me")
  @ResponseStatus(HttpStatus.CREATED)
  ParentResponse createProfile(@Valid @RequestBody final ProfileRequest request) {
    return ParentResponse.from(service.createProfile(request.fullName()));
  }

  @PatchMapping("/me")
  ParentResponse updateProfile(@Valid @RequestBody final ProfileRequest request) {
    return service.updateProfile(request.fullName()).stream()
        .findFirst()
        .map(ParentResponse::from)
        .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
            HttpStatus.NOT_FOUND, "Profile not found"));
  }

  @PostMapping("/families")
  @ResponseStatus(HttpStatus.CREATED)
  FamilyResponse createFamily(@Valid @RequestBody final CreateFamilyRequest request) {
    return FamilyResponse.from(service.createFamily(
        request.name(), request.timeZone(), request.fullName()));
  }

  @GetMapping("/families")
  List<FamilyResponse> listFamilies() {
    return service.listFamilies().stream().map(FamilyResponse::from).toList();
  }

  @GetMapping("/families/{familyId}")
  FamilyResponse getFamily(@PathVariable final String familyId) {
    return FamilyResponse.from(service.getFamily(CoparentIds.parse(familyId)));
  }

  @PatchMapping("/families/{familyId}")
  FamilyResponse updateFamily(
      @PathVariable final String familyId,
      @RequestBody final UpdateFamilyRequest request) {
    return FamilyResponse.from(service.updateFamily(
        CoparentIds.parse(familyId), request.name(), request.timeZone()));
  }

  @DeleteMapping("/families/{familyId}")
  MessageResponse deleteFamily(@PathVariable final String familyId) {
    service.deleteFamily(CoparentIds.parse(familyId));
    return new MessageResponse("Family deleted successfully");
  }

  @GetMapping("/families/{familyId}/parents")
  List<ParentResponse> listParents(@PathVariable final String familyId) {
    return service.listParents(CoparentIds.parse(familyId)).stream()
        .map(ParentResponse::from).toList();
  }

  @PatchMapping("/parents/{parentId}/role")
  ParentResponse updateRole(
      @PathVariable final String parentId,
      @Valid @RequestBody final RoleRequest request) {
    return ParentResponse.from(service.updateRole(CoparentIds.parse(parentId), request.role()));
  }

  record ProfileRequest(@NotBlank String fullName) {
  }

  record CreateFamilyRequest(@NotBlank String name, @NotBlank String timeZone, String fullName) {
  }

  record UpdateFamilyRequest(String name, String timeZone) {
  }

  record RoleRequest(@NotBlank String role) {
  }

  record MessageResponse(String message) {
  }

  record CurrentUserResponse(
      String auth0Id,
      String email,
      List<ParentResponse> profiles,
      boolean isNewUser
  ) {
    static CurrentUserResponse from(final FamilyService.CurrentUser current) {
      return new CurrentUserResponse(current.auth0Id(), current.email(),
          current.profiles().stream().map(ParentResponse::from).toList(), current.newUser());
    }
  }

  record FamilyResponse(
      String id,
      String name,
      String timeZone,
      List<String> parentIds,
      List<String> childIds,
      List<String> invitationIds,
      Instant createdAt
  ) {
    static FamilyResponse from(final Family family) {
      return new FamilyResponse(family.id().toHexString(), family.name(), family.timeZone(),
          ids(family.parentIds()), ids(family.childIds()), ids(family.invitationIds()),
          family.createdAt());
    }

    private static List<String> ids(final List<ObjectId> values) {
      return values.stream().map(ObjectId::toHexString).toList();
    }
  }

  record ParentResponse(
      String id,
      String familyId,
      String fullName,
      String email,
      String role,
      String status,
      String color,
      String avatarUrl,
      Instant lastSignedInAt
  ) {
    static ParentResponse from(final Parent parent) {
      return new ParentResponse(parent.id().toHexString(),
          parent.familyId() == null ? null : parent.familyId().toHexString(), parent.fullName(),
          parent.email(), parent.role(), parent.status(), parent.color(), parent.avatarUrl(),
          parent.lastSignedInAt());
    }
  }
}
