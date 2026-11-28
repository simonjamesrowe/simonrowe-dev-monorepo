package com.simonrowe.coparent.document;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.DocumentNotice;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.model.Parent;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Batches document changes into one email per recipient (spec 057 §2.2).
 *
 * <p>Every change upserts the other active parents' {@link DocumentNotice} and moves its send
 * time to fifteen minutes on, capped at two hours after the batch began ({@link NoticeWindow}).
 * A job each minute claims every due notice with an atomic find-and-remove, then sends it, so a
 * change landing while a notice is being sent goes into a fresh batch rather than being deleted
 * unsent. Recording never fails the change that caused it, and sending is best-effort.
 */
@Service
public class DocumentNoticeService {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentNoticeService.class);
  private static final int MAX_PER_TICK = 200;

  private final ParentRepository parents;
  private final DocumentMailer mailer;
  private final CoparentProperties properties;
  private final MongoTemplate mongoTemplate;
  private final Clock clock;

  @Autowired
  public DocumentNoticeService(
      final ParentRepository parents,
      final DocumentMailer mailer,
      final CoparentProperties properties,
      @Qualifier("coparentMongoTemplate") final MongoTemplate mongoTemplate) {
    this(parents, mailer, properties, mongoTemplate, Clock.systemUTC());
  }

  DocumentNoticeService(
      final ParentRepository parents,
      final DocumentMailer mailer,
      final CoparentProperties properties,
      final MongoTemplate mongoTemplate,
      final Clock clock) {
    this.parents = parents;
    this.mailer = mailer;
    this.properties = properties;
    this.mongoTemplate = mongoTemplate;
    this.clock = clock;
  }

  /** Adds a change to every other active parent's waiting email. Never throws. */
  public void record(final ObjectId familyId, final Parent actor, final ObjectId documentId,
      final DocumentNotice.Change change) {
    final Instant now = clock.instant();
    final DocumentNotice.Item item = new DocumentNotice.Item(documentId, change, actor.id(), now);
    for (final Parent recipient : parents.findByFamilyIdAndStatus(familyId,
        CoparentAccessPolicy.ACTIVE)) {
      if (recipient.id().equals(actor.id())) {
        continue;
      }
      try {
        upsert(familyId, recipient.id(), item, now);
      } catch (RuntimeException failure) {
        // The change itself is saved; an email that does not go is the lesser failure.
        LOG.warn("Could not record a document notice: {}", failure.getMessage());
      }
    }
  }

  private void upsert(final ObjectId familyId, final ObjectId recipientId,
      final DocumentNotice.Item item, final Instant now) {
    final Query query = Query.query(Criteria.where("familyId").is(familyId)
        .and("recipientParentId").is(recipientId));
    final Update update = new Update().push("items", item).setOnInsert("firstAt", now);
    DocumentNotice notice;
    try {
      notice = mongoTemplate.findAndModify(query, update,
          FindAndModifyOptions.options().upsert(true).returnNew(true), DocumentNotice.class);
    } catch (DuplicateKeyException race) {
      // Two changes created the row at once; the unique index kept one, so join it.
      notice = mongoTemplate.findAndModify(query, update,
          FindAndModifyOptions.options().returnNew(true), DocumentNotice.class);
    }
    if (notice == null) {
      return;
    }
    // A second write, after the push: until it lands the row has no sendAfter (new) or its
    // previous one (existing), and either way the job never deletes the item unsent.
    mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(notice.id())),
        new Update().set("sendAfter", NoticeWindow.sendAfter(notice.firstAt(), now)),
        DocumentNotice.class);
  }

  /** Sends every notice whose time has come. */
  @Scheduled(
      initialDelayString = "${coparent.documents.notice-interval:PT1M}",
      fixedDelayString = "${coparent.documents.notice-interval:PT1M}")
  public void sendDue() {
    if (!properties.enabled()) {
      return;
    }
    sendDue(clock.instant());
  }

  /** Claims and sends the notices due at {@code now}; returns how many were claimed. */
  int sendDue(final Instant now) {
    int claimed = 0;
    while (claimed < MAX_PER_TICK) {
      final DocumentNotice notice = mongoTemplate.findAndRemove(
          Query.query(Criteria.where("sendAfter").lte(now)), DocumentNotice.class);
      if (notice == null) {
        break;
      }
      claimed++;
      try {
        send(notice);
      } catch (RuntimeException failure) {
        LOG.warn("Could not send a document notice: {}", failure.getMessage());
      }
    }
    return claimed;
  }

  private void send(final DocumentNotice notice) {
    final Parent recipient = parents.findByIdAndFamilyIdAndStatus(notice.recipientParentId(),
        notice.familyId(), CoparentAccessPolicy.ACTIVE).orElse(null);
    if (recipient == null || notice.items().isEmpty()) {
      return;
    }
    final Set<ObjectId> documentIds = new LinkedHashSet<>();
    final Set<ObjectId> actorIds = new LinkedHashSet<>();
    notice.items().forEach(item -> {
      documentIds.add(item.documentId());
      actorIds.add(item.byParentId());
    });
    final Map<ObjectId, String> titles = new HashMap<>();
    mongoTemplate.find(Query.query(Criteria.where("_id").in(documentIds)
            .and("familyId").is(notice.familyId())), FamilyDocument.class)
        .forEach(document -> titles.put(document.id(), document.title()));
    final List<Parent> actors = parents.findAllById(actorIds).stream()
        .filter(parent -> notice.familyId().equals(parent.familyId())).toList();
    final List<DocumentNoticeText.Line> lines = notice.items().stream()
        .filter(item -> titles.containsKey(item.documentId()))
        .map(item -> new DocumentNoticeText.Line(item.change(), titles.get(item.documentId()),
            item.documentId()))
        .toList();
    if (lines.isEmpty()) {
      return;
    }
    mailer.batch(recipient, actors.size() == 1 ? actors.getFirst() : null, lines);
  }
}
