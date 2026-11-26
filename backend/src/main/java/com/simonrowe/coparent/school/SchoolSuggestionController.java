package com.simonrowe.coparent.school;

import com.simonrowe.coparent.family.ChildController;
import com.simonrowe.coparent.family.ChildService;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.validation.constraints.NotNull;
import org.bson.types.ObjectId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** School events offered to a family, and linking a child to the school calendar. */
@RestController
@RequestMapping("/api/coparent")
public class SchoolSuggestionController {

  private final SchoolSuggestionService service;
  private final ChildService children;
  private final SchoolSuggestionSync sync;
  private final Clock clock;

  public SchoolSuggestionController(final SchoolSuggestionService service,
      final ChildService children, final SchoolSuggestionSync sync, final Clock clock) {
    this.service = service;
    this.children = children;
    this.sync = sync;
    this.clock = clock;
  }

  @GetMapping("/families/{familyId}/school-suggestions")
  List<SuggestionResponse> waiting(@PathVariable final String familyId) {
    return service.waiting(CoparentIds.parse(familyId)).stream()
        .map(SuggestionResponse::from).toList();
  }

  @PostMapping("/families/{familyId}/school-suggestions/{suggestionId}/accept")
  SuggestionResponse accept(@PathVariable final String familyId,
      @PathVariable final String suggestionId) {
    return SuggestionResponse.from(service.accept(CoparentIds.parse(familyId),
        CoparentIds.parse(suggestionId)));
  }

  @PostMapping("/families/{familyId}/school-suggestions/{suggestionId}/dismiss")
  SuggestionResponse dismiss(@PathVariable final String familyId,
      @PathVariable final String suggestionId) {
    return SuggestionResponse.from(service.dismiss(CoparentIds.parse(familyId),
        CoparentIds.parse(suggestionId)));
  }

  @PostMapping("/families/{familyId}/school-suggestions/{suggestionId}/review")
  SuggestionResponse review(@PathVariable final String familyId,
      @PathVariable final String suggestionId,
      @Validated @RequestBody final ReviewRequest request) {
    return SuggestionResponse.from(service.resolveReview(CoparentIds.parse(familyId),
        CoparentIds.parse(suggestionId), request.action()));
  }

  /** Links a child, then looks for school events for them straight away. */
  @PutMapping("/children/{childId}/school-calendar")
  ChildController.ChildResponse link(@PathVariable final String childId,
      @RequestBody final LinkRequest request) {
    final var saved = children.linkSchoolCalendar(CoparentIds.parse(childId),
        request.classTeacher(), request.yearGroup(),
        LocalDate.now(clock.withZone(SchoolSuggestionSync.LONDON)));
    sync.request();
    return ChildController.ChildResponse.from(saved);
  }

  @DeleteMapping("/children/{childId}/school-calendar")
  ChildController.ChildResponse unlink(@PathVariable final String childId) {
    return ChildController.ChildResponse.from(
        children.unlinkSchoolCalendar(CoparentIds.parse(childId)));
  }

  record ReviewRequest(@NotNull SchoolSuggestionService.ReviewAction action) {
  }

  record LinkRequest(String classTeacher, String yearGroup) {
  }

  /** A suggestion as the app shows it. */
  record SuggestionResponse(
      String id,
      String status,
      String review,
      List<String> childIds,
      SchoolEventSuggestion.Details details,
      SchoolEventSuggestion.Details proposed,
      List<String> sourceTitles,
      String calendarEventId
  ) {
    static SuggestionResponse from(final SchoolEventSuggestion suggestion) {
      return new SuggestionResponse(suggestion.id().toHexString(),
          suggestion.status().name().toLowerCase(Locale.ROOT),
          suggestion.review() == null ? null
              : suggestion.review().name().toLowerCase(Locale.ROOT),
          suggestion.childIds().stream().map(ObjectId::toHexString).toList(),
          suggestion.details(), suggestion.proposed(), suggestion.sourceTitles(),
          suggestion.calendarEventId() == null ? null
              : suggestion.calendarEventId().toHexString());
    }
  }
}
