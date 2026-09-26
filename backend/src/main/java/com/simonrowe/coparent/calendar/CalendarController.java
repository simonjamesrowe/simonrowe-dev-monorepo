package com.simonrowe.coparent.calendar;

import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.EventCategory;
import com.simonrowe.coparent.model.ScheduleChangeRequest;
import com.simonrowe.coparent.shared.CoparentIds;
import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract for calendar events and family-defined categories. */
@RestController
@RequestMapping("/api/coparent/families/{familyId}")
public class CalendarController {

  private final CalendarService service;

  public CalendarController(final CalendarService service) {
    this.service = service;
  }

  @PostMapping("/events")
  @ResponseStatus(HttpStatus.CREATED)
  EventResponse createEvent(
      @PathVariable final String familyId,
      @RequestBody final EventRequest request) {
    return EventResponse.from(service.createEvent(
        CoparentIds.parse(familyId), request.values()));
  }

  @GetMapping("/events")
  List<EventResponse> listEvents(@PathVariable final String familyId) {
    return service.listEvents(CoparentIds.parse(familyId)).stream()
        .map(EventResponse::from).toList();
  }

  @GetMapping("/events/{eventId}")
  EventResponse getEvent(
      @PathVariable final String familyId,
      @PathVariable final String eventId) {
    return EventResponse.from(service.getEvent(
        CoparentIds.parse(familyId), CoparentIds.parse(eventId)));
  }

  @PutMapping("/events/{eventId}")
  EventResponse updateEvent(
      @PathVariable final String familyId,
      @PathVariable final String eventId,
      @RequestBody final EventRequest request) {
    return EventResponse.from(service.updateEvent(CoparentIds.parse(familyId),
        CoparentIds.parse(eventId), request.values()));
  }

  @DeleteMapping("/events/{eventId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteEvent(
      @PathVariable final String familyId,
      @PathVariable final String eventId) {
    service.deleteEvent(CoparentIds.parse(familyId), CoparentIds.parse(eventId));
  }

  @PutMapping("/events/{eventId}/skipped-dates/{date}")
  EventResponse skipOccurrence(
      @PathVariable final String familyId,
      @PathVariable final String eventId,
      @PathVariable final String date) {
    return EventResponse.from(service.skipOccurrence(CoparentIds.parse(familyId),
        CoparentIds.parse(eventId), date));
  }

  @DeleteMapping("/events/{eventId}/skipped-dates/{date}")
  EventResponse restoreOccurrence(
      @PathVariable final String familyId,
      @PathVariable final String eventId,
      @PathVariable final String date) {
    return EventResponse.from(service.restoreOccurrence(CoparentIds.parse(familyId),
        CoparentIds.parse(eventId), date));
  }

  @PostMapping("/event-categories")
  @ResponseStatus(HttpStatus.CREATED)
  CategoryResponse createCategory(
      @PathVariable final String familyId,
      @RequestBody final CategoryRequest request) {
    return CategoryResponse.from(service.createCategory(
        CoparentIds.parse(familyId), request.values()));
  }

  @GetMapping("/event-categories")
  List<CategoryResponse> listCategories(@PathVariable final String familyId) {
    return service.listCategories(CoparentIds.parse(familyId)).stream()
        .map(CategoryResponse::from).toList();
  }

  @GetMapping("/event-categories/{categoryId}")
  CategoryResponse getCategory(
      @PathVariable final String familyId,
      @PathVariable final String categoryId) {
    return CategoryResponse.from(service.getCategory(
        CoparentIds.parse(familyId), CoparentIds.parse(categoryId)));
  }

  @PutMapping("/event-categories/{categoryId}")
  CategoryResponse updateCategory(
      @PathVariable final String familyId,
      @PathVariable final String categoryId,
      @RequestBody final CategoryRequest request) {
    return CategoryResponse.from(service.updateCategory(CoparentIds.parse(familyId),
        CoparentIds.parse(categoryId), request.values()));
  }

  @DeleteMapping("/event-categories/{categoryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteCategory(
      @PathVariable final String familyId,
      @PathVariable final String categoryId) {
    service.deleteCategory(CoparentIds.parse(familyId), CoparentIds.parse(categoryId));
  }

  record EventRequest(
      String type,
      String title,
      Instant startDate,
      Instant endDate,
      String startTime,
      String endTime,
      Boolean allDay,
      String parentId,
      List<String> parentIds,
      List<String> childIds,
      String location,
      String notes,
      CalendarEvent.Recurring recurring
  ) {
    CalendarService.EventValues values() {
      return new CalendarService.EventValues(type, title, startDate, endDate, startTime, endTime,
          allDay == null || allDay, id(parentId), ids(parentIds), ids(childIds), location, notes,
          recurring);
    }

    private static ObjectId id(final String value) {
      return value == null || value.isBlank() ? null : CoparentIds.parse(value);
    }

    private static List<ObjectId> ids(final List<String> values) {
      return values == null ? List.of() : values.stream().map(CoparentIds::parse).toList();
    }
  }

  record CategoryRequest(
      String name,
      String icon,
      String color,
      Boolean isDefault
  ) {
    CalendarService.CategoryValues values() {
      return new CalendarService.CategoryValues(name, icon, color,
          isDefault != null && isDefault);
    }
  }

  record EventResponse(
      String id,
      String familyId,
      String type,
      String title,
      Instant startDate,
      Instant endDate,
      String startTime,
      String endTime,
      boolean allDay,
      String parentId,
      List<String> parentIds,
      List<String> childIds,
      String location,
      String notes,
      CalendarEvent.Recurring recurring
  ) {
    static EventResponse from(final CalendarEvent event) {
      return new EventResponse(event.id().toHexString(), event.familyId().toHexString(),
          event.type(), event.title(), event.startDate(), event.endDate(), event.startTime(),
          event.endTime(), event.allDay(), hex(event.parentId()), ids(event.parentIds()),
          ids(event.childIds()), event.location(), event.notes(), event.recurring());
    }

    private static String hex(final ObjectId id) {
      return id == null ? null : id.toHexString();
    }

    private static List<String> ids(final List<ObjectId> values) {
      return values.stream().map(ObjectId::toHexString).toList();
    }
  }

  record CategoryResponse(
      String id,
      String familyId,
      String name,
      String icon,
      String color,
      boolean isDefault,
      boolean isSystem
  ) {
    static CategoryResponse from(final EventCategory category) {
      return new CategoryResponse(category.id().toHexString(),
          category.familyId().toHexString(), category.name(), category.icon(), category.color(),
          category.defaultCategory(), category.system());
    }
  }
}
