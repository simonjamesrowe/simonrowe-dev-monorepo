package com.simonrowe.coparent.family;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
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

/** HTTP contract for private, family-scoped child profiles. */
@RestController
@RequestMapping("/api/coparent")
public class ChildController {

  private final ChildService service;

  public ChildController(final ChildService service) {
    this.service = service;
  }

  @PostMapping("/families/{familyId}/children")
  @ResponseStatus(HttpStatus.CREATED)
  ChildResponse create(
      @PathVariable final String familyId,
      @Valid @RequestBody final CreateChildRequest request) {
    return ChildResponse.from(service.create(CoparentIds.parse(familyId), request.fullName(),
        request.dateOfBirth(), request.school(), request.medicalNotes()));
  }

  @GetMapping("/families/{familyId}/children")
  List<ChildResponse> list(@PathVariable final String familyId) {
    return service.list(CoparentIds.parse(familyId)).stream().map(ChildResponse::from).toList();
  }

  @GetMapping("/children/{childId}")
  ChildResponse get(@PathVariable final String childId) {
    return ChildResponse.from(service.get(CoparentIds.parse(childId)));
  }

  @PatchMapping("/children/{childId}")
  ChildResponse update(
      @PathVariable final String childId,
      @RequestBody final UpdateChildRequest request) {
    return ChildResponse.from(service.update(CoparentIds.parse(childId), request.fullName(),
        request.dateOfBirth(), request.school(), request.medicalNotes()));
  }

  @DeleteMapping("/children/{childId}")
  FamilyController.MessageResponse delete(@PathVariable final String childId) {
    service.delete(CoparentIds.parse(childId));
    return new FamilyController.MessageResponse("Child deleted successfully");
  }

  record CreateChildRequest(
      @NotBlank String fullName,
      @NotNull LocalDate dateOfBirth,
      String school,
      String medicalNotes
  ) {
  }

  record UpdateChildRequest(
      String fullName,
      LocalDate dateOfBirth,
      String school,
      String medicalNotes
  ) {
  }

  record ChildResponse(
      String id,
      String familyId,
      String fullName,
      LocalDate dateOfBirth,
      String school,
      String medicalNotes,
      String avatarUrl
  ) {
    static ChildResponse from(final Child child) {
      return new ChildResponse(child.id().toHexString(), child.familyId().toHexString(),
          child.fullName(), child.dateOfBirth(), child.school(), child.medicalNotes(),
          child.avatarUrl());
    }
  }
}
