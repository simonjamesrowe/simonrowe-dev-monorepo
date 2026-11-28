package com.simonrowe.coparent.document;

import com.mongodb.client.result.UpdateResult;
import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ChildRepository;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Emails both active parents when an unarchived document's expiry or review date comes near
 * (spec 057 §2.2), once a day at 08:00 London time.
 *
 * <p>The thresholds are worked out by {@link ReminderWindows}. Before anything is sent, the
 * reminder's keys go into {@code remindersSent} with an update conditional on the key being
 * absent, so two instances, or a re-run after a crash, cannot both send. That update also
 * bumps {@code version}, because every other write replaces the whole document guarded on it:
 * without the bump, an edit that read the document a moment earlier would write the old
 * {@code remindersSent} back and the reminder would go again the next day. The cost is that a
 * parent editing at exactly 08:00 is asked to reload once.
 */
@Service
public class DocumentReminderService {

  static final ZoneId LONDON = ZoneId.of("Europe/London");
  private static final Logger LOG = LoggerFactory.getLogger(DocumentReminderService.class);

  private final ParentRepository parents;
  private final ChildRepository children;
  private final DocumentMailer mailer;
  private final CoparentProperties properties;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  @Autowired
  public DocumentReminderService(
      final ParentRepository parents,
      final ChildRepository children,
      final DocumentMailer mailer,
      final CoparentProperties properties,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this(parents, children, mailer, properties, mongoTemplate, Clock.systemUTC());
  }

  DocumentReminderService(
      final ParentRepository parents,
      final ChildRepository children,
      final DocumentMailer mailer,
      final CoparentProperties properties,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.parents = parents;
    this.children = children;
    this.mailer = mailer;
    this.properties = properties;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  /** The daily run. */
  @Scheduled(cron = "${coparent.documents.reminder-cron:0 0 8 * * *}", zone = "Europe/London")
  public void sendReminders() {
    if (!properties.enabled()) {
      return;
    }
    sendReminders(LocalDate.now(clock.withZone(LONDON)));
  }

  /** Sends what is due on {@code today}; returns how many reminders were claimed. */
  int sendReminders(final LocalDate today) {
    final List<FamilyDocument> candidates = mongoTemplate.find(Query.query(
        Criteria.where("archivedAt").is(null)
            .and("dueKind").ne(null)
            .and("dueOn").gte(today).lte(today.plus(ReminderWindows.LOOKAHEAD))),
        FamilyDocument.class);
    int claimed = 0;
    for (final FamilyDocument document : candidates) {
      final Optional<ReminderWindows.Due> due = ReminderWindows.due(today, document.dueOn(),
          document.dueKind(), document.remindersSent());
      if (due.isEmpty() || !claim(document, due.get())) {
        continue;
      }
      claimed++;
      try {
        send(document, due.get(), today);
      } catch (RuntimeException failure) {
        LOG.warn("Could not send a document reminder: {}", failure.getMessage());
      }
    }
    return claimed;
  }

  private boolean claim(final FamilyDocument document, final ReminderWindows.Due due) {
    final UpdateResult result = mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(document.id())
            .and("archivedAt").is(null)
            .and("dueOn").is(document.dueOn())
            .and("remindersSent").ne(due.key())),
        new Update().addToSet("remindersSent").each(due.keys().toArray())
            .inc("version", 1),
        FamilyDocument.class);
    return result.getModifiedCount() == 1;
  }

  private void send(final FamilyDocument document, final ReminderWindows.Due due,
      final LocalDate today) {
    final Map<ObjectId, String> names = children.findByFamilyIdAndDeletedAtIsNull(
            document.familyId()).stream()
        .collect(Collectors.toMap(Child::id, DocumentReminderService::firstName));
    final List<String> named = document.childIds().stream().map(names::get)
        .filter(name -> name != null && !name.isBlank()).toList();
    final String forWhom = named.isEmpty() ? "" : " (" + String.join(" and ", named) + ")";
    final String inAbout = DocumentMailer.inAbout(today, document.dueOn(), due.threshold());
    for (final Parent parent : parents.findByFamilyIdAndStatus(document.familyId(),
        CoparentAccessPolicy.ACTIVE)) {
      mailer.reminder(parent, document, forWhom, inAbout);
    }
  }

  private static String firstName(final Child child) {
    final String name = child.fullName() == null ? "" : child.fullName().trim();
    final int space = name.indexOf(' ');
    return space < 0 ? name : name.substring(0, space);
  }
}
