package com.simonrowe.coparent.school;

import com.simonrowe.coparent.config.CoparentProperties;
import com.simonrowe.coparent.model.CalendarEvent;
import com.simonrowe.coparent.model.Child;
import com.simonrowe.coparent.persistence.ParentRepository;
import com.simonrowe.coparent.shared.CoparentAccessPolicy;
import com.simonrowe.school.chat.EventDuplicates;
import com.simonrowe.school.ingest.SchoolEventsRefreshed;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.Visibility;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Offers Term Time's school events to the families whose children are linked to the school, after
 * every school sync.
 *
 * <ol>
 *   <li><b>Review what was offered before.</b> A pending suggestion follows the school's changes
 *       silently, and goes away when the school no longer states it. An accepted one is never
 *       changed behind the family's back: a move or a removal is put back in the list to be
 *       approved again.</li>
 *   <li><b>Offer what is new.</b> Every upcoming event not yet in a suggestion is matched to
 *       children by rule ({@link SchoolEventMatcher}), grouped with the school's other rows for
 *       the same event by rule ({@link EventDuplicates}) and then, optionally, by a model
 *       ({@link SchoolEventMerger}), and saved as pending.</li>
 *   <li><b>Email once</b>, to both parents, when this run left anything new to approve.</li>
 * </ol>
 *
 * <p>Runs on its own thread, so a model call never holds up the school's scheduler, and one run
 * at a time. Requests that arrive during a run collapse into one more run.
 *
 * <p>Calendar-feed rows are never deleted by Term Time, so an event the school moves in its feed
 * keeps its old row: the new date arrives as a new suggestion and the accepted old one stays on the
 * calendar. Only rows Term Time does delete (a letter edited or read again) are reported as
 * removed.
 */
@Component
public class SchoolSuggestionSync {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolSuggestionSync.class);

  /** Longer than a half term; term-long rows ("Autumn Term 2") are not calendar events. */
  static final int MAX_SPAN_DAYS = 14;
  static final ZoneId LONDON = ZoneId.of("Europe/London");

  private static final List<SchoolEventSuggestion.Status> OPEN = List.of(
      SchoolEventSuggestion.Status.PENDING, SchoolEventSuggestion.Status.ACCEPTED);

  private final SchoolEventRepository schoolEvents;
  private final MongoTemplate coparent;
  private final SchoolEventMerger merger;
  private final SchoolSuggestionMailer mailer;
  private final ParentRepository parents;
  private final CoparentProperties coparentProperties;
  private final SchoolCalendarProperties properties;
  private final Clock clock;
  private final ReentrantLock running = new ReentrantLock();
  private final AtomicBoolean queued = new AtomicBoolean();
  private final ExecutorService worker = Executors.newSingleThreadExecutor(
      Thread.ofVirtual().name("school-suggestions").factory());

  public SchoolSuggestionSync(
      final SchoolEventRepository schoolEvents,
      @Qualifier("coparentMongoTemplate") final MongoTemplate coparent,
      final SchoolEventMerger merger,
      final SchoolSuggestionMailer mailer,
      final ParentRepository parents,
      final CoparentProperties coparentProperties,
      final SchoolCalendarProperties properties,
      final Clock clock) {
    this.schoolEvents = schoolEvents;
    this.coparent = coparent;
    this.merger = merger;
    this.mailer = mailer;
    this.parents = parents;
    this.coparentProperties = coparentProperties;
    this.properties = properties;
    this.clock = clock;
  }

  /** What one run did, for the log and for tests. */
  public record Report(int families, int created, int flagged, int absorbed, int emailed) {
    static final Report NOTHING = new Report(0, 0, 0, 0, 0);
  }

  /** Runs after each school sync. */
  @EventListener
  public void onSchoolEventsRefreshed(final SchoolEventsRefreshed refreshed) {
    request();
  }

  /** Asks for a run on the worker thread, unless one is already waiting to start. */
  public void request() {
    if (!enabled() || !queued.compareAndSet(false, true)) {
      return;
    }
    worker.execute(() -> {
      queued.set(false);
      try {
        run();
      } catch (RuntimeException failure) {
        LOG.warn("School event suggestions failed: {}", failure.getMessage(), failure);
      }
    });
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
  }

  /**
   * Reviews and offers school events for every linked family, now, on the calling thread.
   *
   * @return what it did
   */
  public Report run() {
    if (!enabled()) {
      return Report.NOTHING;
    }
    running.lock();
    try {
      return runLocked();
    } finally {
      running.unlock();
    }
  }

  private Report runLocked() {
    final LocalDate today = LocalDate.now(clock.withZone(LONDON));
    final List<Child> linked = coparent.find(Query.query(Criteria.where("schoolCalendar").ne(null)
        .and("deletedAt").is(null)), Child.class);
    if (linked.isEmpty()) {
      return Report.NOTHING;
    }
    final Map<ObjectId, List<Child>> byFamily = linked.stream()
        .collect(Collectors.groupingBy(Child::familyId, LinkedHashMap::new, Collectors.toList()));
    final ObjectId mailboxFamily = mailboxFamily();
    final boolean anyRestricted = mailboxFamily != null && byFamily.containsKey(mailboxFamily);
    final List<SchoolEvent> window = schoolEvents.findOverlapping(today,
            today.plusDays(properties.horizonDays()), anyRestricted
                ? List.of(Visibility.PUBLIC, Visibility.RESTRICTED) : List.of(Visibility.PUBLIC))
        .stream()
        .filter(event -> event.startDate() != null && !event.endDate().isBefore(today))
        .filter(event -> ChronoUnit.DAYS.between(event.startDate(), event.endDate())
            < MAX_SPAN_DAYS)
        // A stable order, so the same rows are grouped and keyed the same way every run.
        .sorted(Comparator.comparing(SchoolEvent::startDate).thenComparing(SchoolEvent::id))
        .toList();
    Report total = Report.NOTHING;
    for (final Map.Entry<ObjectId, List<Child>> family : byFamily.entrySet()) {
      final Set<Visibility> visible = family.getKey().equals(mailboxFamily)
          ? Set.of(Visibility.PUBLIC, Visibility.RESTRICTED) : Set.of(Visibility.PUBLIC);
      try {
        final Report report = syncFamily(family.getKey(), family.getValue(),
            window.stream().filter(e -> visible.contains(e.visibility())).toList(), visible,
            today);
        total = new Report(total.families() + 1, total.created() + report.created(),
            total.flagged() + report.flagged(), total.absorbed() + report.absorbed(),
            total.emailed() + report.emailed());
      } catch (RuntimeException failure) {
        LOG.warn("School event suggestions failed for one family: {}", failure.getMessage(),
            failure);
      }
    }
    if (total.created() + total.flagged() + total.absorbed() > 0) {
      LOG.info("School event suggestions: {}", total);
    }
    return total;
  }

  private Report syncFamily(final ObjectId familyId, final List<Child> children,
      final List<SchoolEvent> events, final Set<Visibility> visible, final LocalDate today) {
    final List<SchoolEventSuggestion> notices = new ArrayList<>();

    // 1. What was offered before.
    final List<SchoolEventSuggestion> open = coparent.find(Query.query(
        Criteria.where("familyId").is(familyId).and("status").in(OPEN)
            .and("details.endDate").gte(today)), SchoolEventSuggestion.class);
    final Map<String, SchoolEvent> members = new HashMap<>();
    schoolEvents.findAllById(open.stream().flatMap(s -> s.sourceEventIds().stream())
            .collect(Collectors.toSet()))
        .forEach(event -> {
          if (visible.contains(event.visibility())) {
            members.put(event.id(), event);
          }
        });
    for (final SchoolEventSuggestion suggestion : open) {
      review(suggestion, rowsOf(suggestion, members)).ifPresent(notices::add);
    }
    final int flagged = notices.size();

    // 2. What is new.
    final Set<String> claimed = new HashSet<>();
    coparent.find(Query.query(Criteria.where("familyId").is(familyId).and("sourceEventIds")
            .in(events.stream().map(SchoolEvent::id).toList())), SchoolEventSuggestion.class)
        .forEach(suggestion -> claimed.addAll(suggestion.sourceEventIds()));
    final Map<String, List<ObjectId>> childrenOf = new HashMap<>();
    final List<SchoolEvent> fresh = new ArrayList<>();
    for (final SchoolEvent event : events) {
      if (claimed.contains(event.id())) {
        continue;
      }
      final List<ObjectId> forChildren = SchoolEventMatcher.childrenFor(event, children).stream()
          .map(Child::id).toList();
      if (!forChildren.isEmpty()) {
        childrenOf.put(event.id(), forChildren);
        fresh.add(event);
      }
    }
    int created = 0;
    int absorbed = 0;
    if (!fresh.isEmpty()) {
      final Offer offer = offer(familyId, fresh, childrenOf, liveOpen(familyId, today), members);
      created = offer.created().size();
      absorbed = offer.absorbed();
      notices.addAll(offer.created());
    }

    // 3. Email once.
    int emailed = 0;
    if (!notices.isEmpty()) {
      final Map<ObjectId, String> names = new HashMap<>();
      children.forEach(child -> names.put(child.id(), firstName(child.fullName())));
      final var active = parents.findByFamilyIdAndStatus(familyId, CoparentAccessPolicy.ACTIVE);
      mailer.needsApproval(active, notices, names);
      emailed = coparentProperties.emailEnabled() ? active.size() : 0;
    }
    return new Report(1, created, flagged, absorbed, emailed);
  }

  /**
   * Brings one earlier suggestion up to date with the school.
   *
   * @return the suggestion when a parent now needs to look at it again
   */
  private Optional<SchoolEventSuggestion> review(
      final SchoolEventSuggestion suggestion, final List<SchoolEvent> rows) {
    final String fingerprint = SuggestionDetails.fingerprint(rows);
    if (fingerprint.equals(suggestion.sourceFingerprint())) {
      return Optional.empty();
    }
    final Instant now = Instant.now(clock);
    if (suggestion.status() == SchoolEventSuggestion.Status.PENDING) {
      final Update update = rows.isEmpty()
          ? new Update().set("status", SchoolEventSuggestion.Status.WITHDRAWN)
          : new Update().set("details",
              SuggestionDetails.from(rows, suggestion.details().title()));
      coparent.updateFirst(byIdAndStatus(suggestion), update
          .set("sourceFingerprint", fingerprint).set("updatedAt", now),
          SchoolEventSuggestion.class);
      return Optional.empty();
    }
    final boolean onCalendar = suggestion.calendarEventId() != null && coparent.exists(
        Query.query(Criteria.where("_id").is(suggestion.calendarEventId())
            .and("familyId").is(suggestion.familyId()).and("deletedAt").is(null)),
        CalendarEvent.class);
    final Update update = new Update().set("sourceFingerprint", fingerprint)
        .set("updatedAt", now);
    SchoolEventSuggestion.Review review = null;
    SchoolEventSuggestion.Details proposed = null;
    if (onCalendar && rows.isEmpty()) {
      review = SchoolEventSuggestion.Review.REMOVED;
    } else if (onCalendar) {
      final SchoolEventSuggestion.Details current =
          SuggestionDetails.from(rows, suggestion.details().title());
      if (!current.sameOnCalendar(suggestion.details())) {
        review = SchoolEventSuggestion.Review.CHANGED;
        proposed = current;
      }
    }
    update.set("review", review).set("proposed", proposed);
    final SchoolEventSuggestion saved = coparent.findAndModify(byIdAndStatus(suggestion), update,
        FindAndModifyOptions.options().returnNew(true),
        SchoolEventSuggestion.class);
    // Asked about again only when the question is new, not every time the rows are re-read.
    final boolean newQuestion = review != null
        && (review != suggestion.review() || !Objects.equals(proposed, suggestion.proposed()));
    return saved != null && newQuestion ? Optional.of(saved)
        : Optional.empty();
  }

  /** New suggestions made, and how many rows joined an existing one. */
  private record Offer(List<SchoolEventSuggestion> created, int absorbed) {
  }

  private Offer offer(final ObjectId familyId, final List<SchoolEvent> fresh,
      final Map<String, List<ObjectId>> childrenOf, final List<SchoolEventSuggestion> existing,
      final Map<String, SchoolEvent> members) {
    // Grouped by rule, and only ever among rows for the same children.
    final Map<Set<ObjectId>, List<SchoolEvent>> byChildren = new LinkedHashMap<>();
    fresh.forEach(event -> byChildren.computeIfAbsent(Set.copyOf(childrenOf.get(event.id())),
        key -> new ArrayList<>()).add(event));
    final List<SchoolEventMerger.Candidate> candidates = new ArrayList<>();
    for (final List<SchoolEvent> rows : byChildren.values()) {
      for (final List<SchoolEvent> group : EventDuplicates.groups(rows)) {
        candidates.add(new SchoolEventMerger.Candidate("e" + (candidates.size() + 1), group));
      }
    }
    final Set<LocalDate> days = candidates.stream()
        .map(c -> c.rows().getFirst().startDate()).collect(Collectors.toSet());
    final Map<String, SchoolEventSuggestion> existingByKey = new LinkedHashMap<>();
    existing.stream().filter(s -> days.contains(s.details().startDate()))
        .forEach(s -> existingByKey.put("x" + (existingByKey.size() + 1), s));
    final Map<String, SchoolEventMerger.Candidate> byKey = new LinkedHashMap<>();
    candidates.forEach(candidate -> byKey.put(candidate.key(), candidate));

    final List<SchoolEventMerger.Entry> plan = candidates.size() > SchoolEventMerger.MAX_ITEMS
        ? List.of()
        : merger.plan(candidates, existingByKey.entrySet().stream()
            .map(e -> new SchoolEventMerger.Existing(e.getKey(), e.getValue().details().title(),
                e.getValue().details().startDate(), e.getValue().details().sourceTime()))
            .toList());

    final Set<String> placed = new HashSet<>();
    final List<SchoolEventSuggestion> created = new ArrayList<>();
    int absorbed = 0;
    for (final SchoolEventMerger.Entry entry : plan) {
      final List<SchoolEventMerger.Candidate> parts = entry.members().stream()
          .map(byKey::get).filter(Objects::nonNull).toList();
      final SchoolEventSuggestion into = entry.members().stream().map(existingByKey::get)
          .filter(Objects::nonNull).findFirst().orElse(null);
      if (parts.isEmpty() || !consistent(parts, childrenOf, into)) {
        continue;
      }
      parts.forEach(part -> placed.add(part.key()));
      final List<SchoolEvent> rows = parts.stream().flatMap(p -> p.rows().stream()).toList();
      if (into != null) {
        if (absorb(into, rows, members)) {
          absorbed += rows.size();
        }
        continue;
      }
      insert(familyId, rows, childrenOf, entry).ifPresent(created::add);
    }
    for (final SchoolEventMerger.Candidate candidate : candidates) {
      if (!placed.contains(candidate.key())) {
        insert(familyId, candidate.rows(), childrenOf, null).ifPresent(created::add);
      }
    }
    return new Offer(created, absorbed);
  }

  /**
   * A merge the model proposed is used only when every row is on the same day and for the same
   * children, and so is the existing suggestion it joins.
   */
  private static boolean consistent(final List<SchoolEventMerger.Candidate> parts,
      final Map<String, List<ObjectId>> childrenOf, final SchoolEventSuggestion into) {
    final List<SchoolEvent> rows = parts.stream().flatMap(p -> p.rows().stream()).toList();
    final LocalDate day = rows.getFirst().startDate();
    final Set<ObjectId> kids = Set.copyOf(childrenOf.get(rows.getFirst().id()));
    final boolean same = rows.stream().allMatch(row -> row.startDate().equals(day)
        && Set.copyOf(childrenOf.get(row.id())).equals(kids));
    return same && (into == null || (into.details().startDate().equals(day)
        && Set.copyOf(into.childIds()).equals(kids)));
  }

  private boolean absorb(final SchoolEventSuggestion into, final List<SchoolEvent> rows,
      final Map<String, SchoolEvent> members) {
    final List<SchoolEvent> all = Stream.concat(rowsOf(into, members).stream(), rows.stream())
        .toList();
    try {
      // The baseline moves with the new rows, so a reworded copy of an accepted event is not
      // taken for a change the family must approve again.
      return coparent.updateFirst(byIdAndStatus(into), new Update()
              .addToSet("sourceEventIds").each(rows.stream().map(SchoolEvent::id).toArray())
              .addToSet("sourceTitles").each(rows.stream().map(SchoolEvent::title).toArray())
              .set("sourceFingerprint", SuggestionDetails.fingerprint(all))
              .set("updatedAt", Instant.now(clock)), SchoolEventSuggestion.class)
          .getModifiedCount() > 0;
    } catch (DuplicateKeyException raced) {
      return false;
    }
  }

  private Optional<SchoolEventSuggestion> insert(final ObjectId familyId,
      final List<SchoolEvent> rows, final Map<String, List<ObjectId>> childrenOf,
      final SchoolEventMerger.Entry entry) {
    SchoolEventSuggestion.Details details = SuggestionDetails.from(rows,
        entry == null ? null : entry.title());
    if (entry != null && SchoolTimes.isClockTime(entry.startTime())
        && details.startDate().equals(details.endDate())) {
      final String end = SchoolTimes.isClockTime(entry.endTime())
          && entry.endTime().compareTo(entry.startTime()) > 0 ? entry.endTime() : null;
      details = new SchoolEventSuggestion.Details(details.title(), details.startDate(),
          details.endDate(), entry.startTime(), end, details.location(), details.notes(),
          details.sourceTime());
    }
    final Instant now = Instant.now(clock);
    final SchoolEventSuggestion suggestion = new SchoolEventSuggestion(null, familyId,
        rows.stream().map(SchoolEvent::id).distinct().toList(),
        rows.stream().map(SchoolEvent::title).distinct().toList(),
        childrenOf.get(rows.getFirst().id()), details, SuggestionDetails.fingerprint(rows),
        SchoolEventSuggestion.Status.PENDING, null, null, null, null, null, now, now);
    try {
      return Optional.of(coparent.insert(suggestion));
    } catch (DuplicateKeyException raced) {
      // Another run offered one of these rows first; that suggestion stands.
      return Optional.empty();
    }
  }

  private List<SchoolEventSuggestion> liveOpen(final ObjectId familyId, final LocalDate today) {
    return coparent.find(Query.query(Criteria.where("familyId").is(familyId)
        .and("status").in(OPEN).and("details.endDate").gte(today)
        .and("sourceFingerprint").ne(SuggestionDetails.GONE)), SchoolEventSuggestion.class);
  }

  private static List<SchoolEvent> rowsOf(final SchoolEventSuggestion suggestion,
      final Map<String, SchoolEvent> members) {
    return suggestion.sourceEventIds().stream().map(members::get).filter(Objects::nonNull)
        .toList();
  }

  private static Query byIdAndStatus(final SchoolEventSuggestion suggestion) {
    return Query.query(Criteria.where("_id").is(suggestion.id())
        .and("status").is(suggestion.status()));
  }

  private boolean enabled() {
    return coparentProperties.enabled() && properties.enabled();
  }

  private ObjectId mailboxFamily() {
    return ObjectId.isValid(properties.mailboxFamilyId())
        ? new ObjectId(properties.mailboxFamilyId()) : null;
  }

  static String firstName(final String fullName) {
    final String name = fullName == null ? "" : fullName.strip();
    final int space = name.indexOf(' ');
    return space < 0 ? name : name.substring(0, space);
  }
}
