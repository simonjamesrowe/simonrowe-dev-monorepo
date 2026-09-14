package com.simonrowe.school.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** Persistence for {@link SchoolDocument}. */
public interface SchoolDocumentRepository extends MongoRepository<SchoolDocument, String> {

  /**
   * Documents awaiting a human decision on promotion to public, newest first.
   *
   * <p>An explicit query rather than a derived name: the derived form needs four conditions and
   * runs well past the line limit, at which point it stops being readable as a sentence anyway.
   * Name-gate-blocked documents are excluded here rather than filtered by the caller — they are
   * <p>Everything restricted that nobody has ruled on, <b>whatever the classifier proposed</b>.
   * Keying this on {@code proposedVisibility: PUBLIC} left the queue permanently empty: the
   * classifier proposes RESTRICTED for very nearly everything, which is right for a conservative
   * classifier and useless as a queue filter. The symptom was an empty Approvals page beside a
   * documents list full of restricted items nobody could act on.
   *
   * @return documents restricted and undecided, newest publication first
   */
  @Query("{ 'visibility': 'RESTRICTED', 'approvedAt': null, 'declinedAt': null }")
  List<SchoolDocument> findAwaitingApproval();

  /**
   * Finds a document by its source, used to decide insert-versus-update on re-ingest.
   *
   * @param sourceType the source kind
   * @param sourceRef the source reference
   * @return the document if already ingested
   */
  Optional<SchoolDocument> findBySourceTypeAndSourceRef(
      SchoolSourceType sourceType, String sourceRef);

  /**
   * All documents in a given tier, used by the re-embed pass after an approval changes one.
   *
   * @param visibility the tier
   * @return matching documents
   */
  List<SchoolDocument> findByVisibility(Visibility visibility);

  /**
   * What the school published in a date window, newest first, tier-filtered.
   *
   * <p>The half of retrieval that similarity search cannot do, and the reason it exists is the
   * same reason {@code SchoolQueryService} exists for events: "the newsletter from last week"
   * has no useful embedding. Every weekly newsletter in the corpus sits in almost the same place
   * in vector space, so a top-k search over them returns one essentially at random and the
   * assistant reports it as the latest — observed in production answering from a newsletter five
   * weeks stale, and stating so.
   *
   * <p>{@code sourceTypes} is a parameter rather than a constant here so the caller states what
   * it means by a communication; see {@code SchoolQueryService.COMMUNICATION_SOURCES} for the
   * one that matters, which is that {@code CALENDAR_FEED} must never be in it.
   *
   * @param visibilities which tiers the caller may see
   * @param sourceTypes which kinds of document count
   * @param from window start, inclusive
   * @param to window end, inclusive
   * @return matching documents, most recently published first
   */
  @Query(value = "{ 'visibility': { $in: ?0 }, 'sourceType': { $in: ?1 }, "
      + "'publishedAt': { $gte: ?2, $lte: ?3 } }",
      sort = "{ 'publishedAt': -1 }")
  List<SchoolDocument> findPublishedBetween(
      List<Visibility> visibilities, List<SchoolSourceType> sourceTypes, Instant from, Instant to);
}
