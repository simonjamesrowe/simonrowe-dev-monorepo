package com.simonrowe.school.admin;

import com.simonrowe.school.classify.SchoolEventExtractor;
import com.simonrowe.school.ingest.SchoolDocumentWriter;
import com.simonrowe.school.ingest.SchoolEventWriter;
import com.simonrowe.school.ingest.SchoolIngestService;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolEventRepository;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.YearGroups;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Corrects a stored document by hand: its title, its text and the year groups it concerns.
 *
 * <p>The case it exists for is a pasted Year 3 spelling list that Term Time could not find when
 * asked for "the spelling words this week". It had been pasted before year groups reached the
 * chunks, its title said nothing about spellings, and the only way to fix either was to paste it
 * again — which, because a note's id derives from a hash of its text, forked a second note rather
 * than correcting the first.
 *
 * <p>So an edit is made <b>in place</b>. The id and {@code sourceRef} are kept, and so is every
 * human decision on the document — tier, approval, decline — because correcting a typo is never
 * a reason to re-open whether something may be public. Three things then have to follow the
 * edit, and each is silent if missed:
 *
 * <ol>
 *   <li>the chunks are rewritten, because the assistant reads the title, text and year groups
 *       from Elasticsearch, not Mongo — an edit that skipped this would show in the console and
 *       change nothing in an answer;
 *   <li>the events are re-read when the text or year groups changed, and the ones this document
 *       previously produced are removed first, since an event's id is derived from its title and
 *       date and a corrected date would otherwise leave the wrong one standing beside the right;
 *   <li>a note's new addresses are recorded and fetched, exactly as on first paste.
 * </ol>
 *
 * <p>Sources the crawler re-reads (see {@link #isRewrittenByIngest}) can still be edited, but the
 * edit lasts only until the next crawl: the website crawl compares the page against the stored
 * hash, sees the edited text as a change, and writes the page back. That is accepted rather than
 * prevented — pinning edited text against the crawl would freeze a page the school then corrects
 * itself — and the console says so beside the editor.
 */
@Service
public class SchoolDocumentEditor {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolDocumentEditor.class);

  /** The same ceiling a pasted note has, so an edit cannot store more than a paste could. */
  static final int MAX_CHARS = 50_000;

  private final SchoolDocumentRepository documents;
  private final SchoolDocumentWriter documentWriter;
  private final SchoolEventRepository events;
  private final SchoolEventExtractor eventExtractor;
  private final SchoolEventWriter eventWriter;
  private final SchoolIngestService ingestService;
  private final SchoolNoteService notes;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public SchoolDocumentEditor(
      final SchoolDocumentRepository documents,
      final SchoolDocumentWriter documentWriter,
      final SchoolEventRepository events,
      final SchoolEventExtractor eventExtractor,
      final SchoolEventWriter eventWriter,
      final SchoolIngestService ingestService,
      final SchoolNoteService notes) {
    this.documents = documents;
    this.documentWriter = documentWriter;
    this.events = events;
    this.eventExtractor = eventExtractor;
    this.eventWriter = eventWriter;
    this.ingestService = ingestService;
    this.notes = notes;
  }

  /**
   * Applies an edit.
   *
   * @param id the document id
   * @param title the new title; must not be blank, because every citation names it
   * @param body the new text; must not be blank
   * @param yearGroups the year groups it concerns, empty for whole-school
   * @return what was stored and whether the events kept up, or empty for an unknown id
   * @throws IllegalArgumentException when the title or text is blank
   */
  public Optional<Edit> edit(
      final String id, final String title, final String body, final List<String> yearGroups) {
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("A document needs a title — answers cite it by name.");
    }
    if (body == null || body.isBlank()) {
      throw new IllegalArgumentException(
          "The text is empty. Make the document private instead if it should not be used.");
    }
    final String newBody = body.length() > MAX_CHARS ? body.substring(0, MAX_CHARS) : body;
    final List<String> newYears = YearGroups.sanitise(yearGroups);

    return documents.findById(id).map(stored -> {
      final boolean bodyChanged = !Objects.equals(stored.body(), newBody);
      final boolean yearsChanged = !stored.yearGroups().equals(newYears);
      final boolean titleChanged = !Objects.equals(stored.title(), title.trim());
      if (!bodyChanged && !yearsChanged && !titleChanged) {
        return new Edit(stored, false, false);
      }

      final SchoolDocument saved = documentWriter.save(new SchoolDocument(
          stored.id(), stored.sourceType(), stored.sourceRef(), title.trim(), newBody,
          stored.publishedAt(), stored.ingestedAt(), stored.visibility(),
          stored.proposedVisibility(), stored.proposalReason(), stored.approvedBy(),
          stored.approvedAt(), stored.nameGateBlocked(), newYears,
          SchoolDocumentWriter.contentHash(newBody), stored.declinedAt()));
      ingestService.embed(saved);
      LOG.info("Document {} edited by hand (text {}, year groups {}, title {})", id,
          bodyChanged ? "changed" : "unchanged", yearsChanged ? "changed" : "unchanged",
          titleChanged ? "changed" : "unchanged");

      // A title alone does not change what the extractor would read out of the text, so it is
      // not worth a model call.
      final boolean eventRefreshFailed = (bodyChanged || yearsChanged) && !refreshEvents(saved);
      if (bodyChanged && saved.sourceType() == SchoolSourceType.PASTED_NOTE) {
        notes.followLinks(saved);
      }
      return new Edit(saved, true, eventRefreshFailed);
    });
  }

  /**
   * Whether the crawler writes this document's text or metadata back on its next pass.
   *
   * @param document the document
   * @return true when a hand edit is temporary
   */
  public static boolean isRewrittenByIngest(final SchoolDocument document) {
    return switch (document.sourceType()) {
      case WEBSITE_PAGE, CALENDAR_FEED -> true;
      // A PDF linked from the website is never re-downloaded, but its title and year groups are
      // re-derived from the pages linking it at the end of every complete crawl. A PDF that came
      // attached to an email is never looked at again.
      case PDF -> document.sourceRef() != null && document.sourceRef().startsWith("http");
      default -> false;
    };
  }

  /**
   * Re-reads the dated facts, replacing what this document produced before.
   *
   * <p>Extraction runs <b>before</b> anything is deleted. It is a model call and can fail, and a
   * failure must leave the old events in place rather than an edited document with none at all.
   *
   * @return true when the events were replaced, false when extraction failed
   */
  private boolean refreshEvents(final SchoolDocument document) {
    final List<SchoolEvent> extracted;
    try {
      extracted = eventExtractor.extract(document);
    } catch (RuntimeException e) {
      LOG.warn("Re-reading events from edited document {} failed: {}",
          document.id(), e.getMessage());
      return false;
    }
    events.deleteAll(events.findBySourceDocumentIdIn(List.of(document.id())));
    for (SchoolEvent event : extracted) {
      eventWriter.write(event.withYearGroupScope(document.yearGroups()));
    }
    return true;
  }

  /**
   * The outcome of an edit.
   *
   * @param document the document as now stored
   * @param changed false when the edit matched what was already stored, so nothing was rewritten
   * @param eventRefreshFailed true when the events needed re-reading and the extractor failed, so
   *     the previous ones were kept and may no longer match the text
   */
  public record Edit(SchoolDocument document, boolean changed, boolean eventRefreshFailed) {
  }
}
