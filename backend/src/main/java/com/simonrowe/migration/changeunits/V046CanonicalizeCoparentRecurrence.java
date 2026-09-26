package com.simonrowe.migration.changeunits;

import com.simonrowe.coparent.calendar.Recurrence;
import com.simonrowe.coparent.persistence.CoparentMongoOperations;
import io.mongock.api.annotations.ChangeUnit;
import io.mongock.api.annotations.Execution;
import io.mongock.api.annotations.RollbackExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Rewrites CoParent recurrence values to the vocabulary the calendar renders.
 *
 * <p>Before the assistant's tool schema constrained them, an approved proposal could store a
 * weekly series as {@code "WEEKLY"} or with days such as {@code "TUE"}, {@code "TU"} or
 * {@code "Tuesday"}. The browser matches days against lowercase full names, so those series
 * were saved and then never shown on any calendar view. Each value is mapped to its canonical
 * form with {@link Recurrence}; a day that cannot be identified is dropped, which makes the
 * browser fall back to the weekday of the start date rather than to no occurrences at all.
 * No document is removed.
 *
 * <p>Idempotent: canonical values map to themselves, and only documents whose recurrence
 * actually changes are written.
 */
@ChangeUnit(id = "canonicalize-coparent-recurrence", order = "046", author = "simonrowe")
public class V046CanonicalizeCoparentRecurrence {

  private static final Logger log =
      LoggerFactory.getLogger(V046CanonicalizeCoparentRecurrence.class);

  @Execution
  public void execution(final CoparentMongoOperations operations) {
    final MongoTemplate template = operations.template();
    int updated = 0;
    for (final Document event : template.find(
        Query.query(Criteria.where("recurring").ne(null)), Document.class,
        V043CreateCoparentCollections.EVENTS)) {
      final Document recurring = event.get("recurring", Document.class);
      final String frequency = recurring.getString("frequency");
      final String canonicalFrequency = Recurrence.canonicalFrequency(frequency)
          .orElse(frequency);
      final List<String> days = recurring.getList("days", Object.class, List.of()).stream()
          .map(day -> day instanceof String value ? value : null)
          .toList();
      final List<String> canonicalDays = new ArrayList<>();
      days.forEach(day -> Recurrence.canonicalDay(day)
          .filter(name -> !canonicalDays.contains(name))
          .ifPresent(canonicalDays::add));
      if (Objects.equals(frequency, canonicalFrequency) && days.equals(canonicalDays)) {
        continue;
      }
      template.updateFirst(Query.query(Criteria.where("_id").is(event.get("_id"))),
          new Update().set("recurring.frequency", canonicalFrequency)
              .set("recurring.days", canonicalDays),
          V043CreateCoparentCollections.EVENTS);
      updated++;
    }
    log.info("Canonicalized recurrence on {} CoParent events", updated);
  }

  @RollbackExecution
  public void rollback() {
    // The original spellings rendered nothing and are not worth restoring.
  }
}
