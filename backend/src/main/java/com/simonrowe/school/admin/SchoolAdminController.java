package com.simonrowe.school.admin;

import com.simonrowe.school.ingest.SchoolAttachmentStore;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolEvent;
import com.simonrowe.school.model.SchoolLink;
import com.simonrowe.school.model.SchoolLinkRepository;
import com.simonrowe.school.model.SchoolSyncState;
import com.simonrowe.school.model.SchoolSyncStateRepository;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read and operate the Term Time corpus from the admin console.
 *
 * <p>Under {@code /api/admin/**}, so gated on the site admin role by {@code SecurityConfig}.
 * These endpoints expose restricted content — the whole point is to look at what has been
 * ingested before deciding what may be public — so the gate matters more here than on the chat.
 *
 * <p>Queries are built with {@link MongoTemplate} rather than derived repository methods: the
 * filters combine optionally (source, tier, status, free text) and a derived method per
 * combination would be sixteen methods with names nobody can read.
 */
@RestController
@RequestMapping("/api/admin/school")
public class SchoolAdminController {

  /** The sources that exist, in the order the console shows them. */
  private static final List<String> SOURCES = List.of("calendar", "website", "gmail");

  private static final int MAX_PAGE_SIZE = 200;
  private static final int PREVIEW_CHARS = 300;

  private final MongoTemplate mongoTemplate;
  private final SchoolSyncStateRepository syncState;
  private final SchoolApprovalService approvalService;
  private final SchoolIngestTrigger ingestTrigger;
  private final SchoolUsageRepository usage;
  private final SchoolAttachmentStore attachments;
  private final SchoolLinkRepository links;
  private final SchoolLinkFetcher linkFetcher;

  public SchoolAdminController(
      final MongoTemplate mongoTemplate,
      final SchoolSyncStateRepository syncState,
      final SchoolApprovalService approvalService,
      final SchoolIngestTrigger ingestTrigger,
      final SchoolUsageRepository usage,
      final SchoolAttachmentStore attachments,
      final SchoolLinkRepository links,
      final SchoolLinkFetcher linkFetcher) {
    this.mongoTemplate = mongoTemplate;
    this.syncState = syncState;
    this.approvalService = approvalService;
    this.ingestTrigger = ingestTrigger;
    this.usage = usage;
    this.attachments = attachments;
    this.links = links;
    this.linkFetcher = linkFetcher;
  }

  /**
   * Corpus counts and per-source sync state — the overview page.
   *
   * @return counts by source, tier and approval state, plus when each source last ran
   */
  @GetMapping("/status")
  public StatusResponse status() {
    return new StatusResponse(
        countsBy("sourceType"),
        countsBy("visibility"),
        mongoTemplate.count(awaitingApprovalQuery(), SchoolDocument.class),
        mongoTemplate.count(new Query(), SchoolEvent.class),
        eventCountsBy("eventType"),
        sourceStatuses());
  }

  /**
   * Spend and usage.
   *
   * @param days how many days back to report, capped at a year
   * @return totals overall and for the window
   */
  @GetMapping("/usage")
  public UsageResponse usage(@RequestParam(defaultValue = "30") final int days) {
    final int window = Math.min(Math.max(1, days), 365);
    final Instant from = Instant.now().minus(java.time.Duration.ofDays(window));
    final List<SchoolUsage> all = usage.findAll();
    final List<SchoolUsage> recent = all.stream()
        .filter(u -> u.at() != null && u.at().isAfter(from))
        .toList();

    return new UsageResponse(
        window,
        total(all),
        total(recent),
        byKind(recent),
        // Sessions, not people: the public chat has no sign-in, so this counts conversations.
        recent.stream().map(SchoolUsage::sessionId).filter(java.util.Objects::nonNull)
            .distinct().count(),
        recent.stream().map(SchoolUsage::clientHash).filter(java.util.Objects::nonNull)
            .distinct().count(),
        recent.stream().filter(u -> u.kind() == SchoolUsage.Kind.CHAT).count(),
        // Honest flag: any total containing estimated rows is an estimate.
        recent.stream().anyMatch(SchoolUsage::estimated));
  }

  private double total(final List<SchoolUsage> rows) {
    return rows.stream().mapToDouble(SchoolUsage::costUsd).sum();
  }

  private Map<String, Double> byKind(final List<SchoolUsage> rows) {
    final Map<String, Double> costs = new java.util.LinkedHashMap<>();
    for (SchoolUsage.Kind kind : SchoolUsage.Kind.values()) {
      costs.put(kind.name(), rows.stream()
          .filter(u -> u.kind() == kind)
          .mapToDouble(SchoolUsage::costUsd).sum());
    }
    return costs;
  }

  /**
   * A page of documents, filtered and searched.
   *
   * @param page zero-based page number
   * @param size page size, capped
   * @param q free text matched against title and body, or blank for everything
   * @param sourceType restrict to one source, or blank
   * @param visibility restrict to one tier, or blank
   * @param status one of {@code awaiting}, {@code blocked}, {@code approved}, or blank
   * @return the page
   */
  @GetMapping("/documents")
  public PageResponse<DocumentSummary> documents(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "25") final int size,
      @RequestParam(defaultValue = "") final String q,
      @RequestParam(defaultValue = "") final String sourceType,
      @RequestParam(defaultValue = "") final String visibility,
      @RequestParam(defaultValue = "") final String status) {

    final Query query = new Query();
    if (!q.isBlank()) {
      // Case-insensitive substring over title and body. Not a text index: the corpus is a few
      // hundred documents, and a regex scan at that size is instant while a text index would be
      // another Mongock change unit to keep in step with a restore.
      final String escaped = java.util.regex.Pattern.quote(q.trim());
      query.addCriteria(new Criteria().orOperator(
          Criteria.where("title").regex(escaped, "i"),
          Criteria.where("body").regex(escaped, "i")));
    }
    if (!sourceType.isBlank()) {
      query.addCriteria(Criteria.where("sourceType").is(sourceType));
    }
    if (!visibility.isBlank()) {
      query.addCriteria(Criteria.where("visibility").is(visibility));
    }
    switch (status) {
      // "Needs a decision" rather than "the classifier said PUBLIC". Keying on the proposal
      // left the queue permanently empty — the classifier is conservative enough to propose
      // RESTRICTED for nearly everything — which made the approval step invisible. The comment
      // said this before the code did; the criteria below is the fix, not the intent.
      case "awaiting" -> query.addCriteria(Criteria.where("visibility").is("RESTRICTED")
          .and("approvedAt").is(null).and("declinedAt").is(null));
      case "approved" -> query.addCriteria(Criteria.where("approvedAt").ne(null));
      case "declined" -> query.addCriteria(Criteria.where("declinedAt").ne(null));
      default -> { }
    }

    final long total = mongoTemplate.count(query, SchoolDocument.class);
    final PageRequest pageable = PageRequest.of(
        Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE),
        Sort.by(Sort.Direction.DESC, "publishedAt"));
    final List<SchoolDocument> found = mongoTemplate.find(query.with(pageable),
        SchoolDocument.class);
    // One query for the whole page's links rather than one per row.
    final Map<String, List<LinkSummary>> linksByDocument = links
        .findBySourceDocumentIdIn(found.stream().map(SchoolDocument::id).toList()).stream()
        .map(LinkSummary::from)
        .collect(java.util.stream.Collectors.groupingBy(LinkSummary::sourceDocumentId));
    final List<DocumentSummary> items = found.stream()
        .map(d -> DocumentSummary.from(d, attachments.has(d.id()),
            linksByDocument.getOrDefault(d.id(), List.of())))
        .toList();
    return new PageResponse<>(items, total, pageable.getPageNumber(), pageable.getPageSize());
  }

  /**
   * One document in full, for the drawer.
   *
   * @param id the document id
   * @return the document text and metadata, or 404
   */
  @GetMapping("/documents/{id}")
  public ResponseEntity<SchoolDocument> document(@PathVariable final String id) {
    final SchoolDocument found = mongoTemplate.findById(id, SchoolDocument.class);
    return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(found);
  }

  /**
   * Downloads the original PDF for a document, whatever its tier.
   *
   * <p>Unlike the public endpoint, this serves restricted attachments too — deciding whether a
   * document may be published means being able to read the actual document, and this route is
   * behind the admin role.
   *
   * @param id the document id
   * @return the PDF, or 404 when nothing is stored
   */
  @GetMapping("/documents/{id}/file")
  public ResponseEntity<byte[]> file(@PathVariable final String id) {
    final SchoolDocument document = mongoTemplate.findById(id, SchoolDocument.class);
    if (document == null) {
      return ResponseEntity.notFound().build();
    }
    return attachments.read(id)
        .map(bytes -> ResponseEntity.ok()
            .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/pdf")
            .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + id + ".pdf\"")
            .body(bytes))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Fetches a discovered link, on explicit instruction.
   *
   * <p>This is the only route that causes an outbound request to an address found in an email.
   * The ingester never follows them.
   *
   * @param id the link id
   * @return the updated link, or 404
   */
  @PostMapping("/links/{id}/fetch")
  public ResponseEntity<LinkSummary> fetchLink(@PathVariable final String id) {
    return linkFetcher.fetch(id).map(LinkSummary::from).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Declines a discovered link permanently.
   *
   * @param id the link id
   * @return the updated link, or 404
   */
  @PostMapping("/links/{id}/ignore")
  public ResponseEntity<LinkSummary> ignoreLink(@PathVariable final String id) {
    return linkFetcher.ignore(id).map(LinkSummary::from).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * A page of extracted events.
   *
   * @param page zero-based page number
   * @param size page size, capped
   * @param q free text matched against the title
   * @param eventType restrict to one type, or blank
   * @param sourceType restrict to one source, or blank
   * @return the page, soonest first
   */
  @GetMapping("/events")
  public PageResponse<SchoolEvent> events(
      @RequestParam(defaultValue = "0") final int page,
      @RequestParam(defaultValue = "25") final int size,
      @RequestParam(defaultValue = "") final String q,
      @RequestParam(defaultValue = "") final String eventType,
      @RequestParam(defaultValue = "") final String sourceType) {

    final Query query = new Query();
    if (!q.isBlank()) {
      query.addCriteria(Criteria.where("title")
          .regex(java.util.regex.Pattern.quote(q.trim()), "i"));
    }
    if (!eventType.isBlank()) {
      query.addCriteria(Criteria.where("eventType").is(eventType));
    }
    if (!sourceType.isBlank()) {
      query.addCriteria(Criteria.where("sourceType").is(sourceType));
    }

    final long total = mongoTemplate.count(query, SchoolEvent.class);
    final PageRequest pageable = PageRequest.of(
        Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE),
        Sort.by(Sort.Direction.ASC, "startDate"));
    return new PageResponse<>(
        mongoTemplate.find(query.with(pageable), SchoolEvent.class),
        total, pageable.getPageNumber(), pageable.getPageSize());
  }

  /**
   * Approves, declines or revokes many documents at once.
   *
   * <p>Each id is processed independently and the response reports what actually happened to
   * each, so an operator never believes they published more than they did. There is no longer a
   * "refused" outcome: the name gate that produced it has been removed, and approval is now the
   * only thing standing between a document and the public tier.
   *
   * @param request the ids and the action
   * @return per-id outcomes
   */
  @PostMapping("/approvals/bulk")
  public BulkResponse bulk(@org.springframework.web.bind.annotation.RequestBody
      final BulkRequest request) {
    int approved = 0;
    int declined = 0;
    int revoked = 0;
    int missing = 0;

    for (String id : request.ids()) {
      final var result = switch (request.action()) {
        case "approve" -> approvalService.approve(id, request.approver(), request.force());
        case "decline" -> approvalService.decline(id);
        case "revoke" -> approvalService.revoke(id);
        default -> java.util.Optional.<SchoolDocument>empty();
      };
      if (result.isEmpty()) {
        missing++;
        continue;
      }
      final SchoolDocument document = result.get();
      switch (request.action()) {
        case "approve" -> {
          if (document.visibility() == Visibility.PUBLIC) {
            approved++;
          }
        }
        case "decline" -> declined++;
        case "revoke" -> revoked++;
        default -> { }
      }
    }
    return new BulkResponse(approved, declined, revoked, missing);
  }

  /**
   * Starts an ingest run.
   *
   * @param source {@code calendar}, {@code website} or {@code gmail}
   * @return 202 when started, 409 when that source is already running
   */
  @PostMapping("/ingest/{source}")
  public ResponseEntity<Map<String, String>> ingest(@PathVariable final String source) {
    if (!SOURCES.contains(source)) {
      return ResponseEntity.badRequest().body(Map.of("detail", "Unknown source " + source));
    }
    if (!ingestTrigger.trigger(source)) {
      return ResponseEntity.status(409)
          .body(Map.of("detail", "A " + source + " ingest is already running"));
    }
    final String note = "website".equals(source)
        ? "Website crawl started. It takes around 30 minutes — the school's robots.txt asks for "
            + "a ten second delay between pages."
        : source + " ingest started";
    return ResponseEntity.accepted().body(Map.of("detail", note));
  }

  /**
   * The state of all three sources, whether or not they have ever completed.
   *
   * <p>Built from the canonical source list and then merged with whatever sync state exists —
   * <b>not</b> from the sync-state rows. A row is only written when a run finishes, so deriving
   * the list from Mongo meant a source that had never completed was absent from the response
   * entirely: the page showed "never", and could never show "Running…" for a first run, which
   * is exactly when someone is watching.
   *
   * @return one entry per source, in a fixed order
   */
  private List<SourceStatus> sourceStatuses() {
    final Map<String, SchoolSyncState> byId = new java.util.HashMap<>();
    syncState.findAll().forEach(state -> byId.put(state.id(), state));
    return SOURCES.stream().map(source -> {
      final SchoolSyncState state = byId.get(source);
      return new SourceStatus(
          source,
          state == null ? null : state.lastSuccessAt(),
          state == null ? null : state.lastFailureAt(),
          state == null ? null : state.lastFailureReason(),
          ingestTrigger.isRunning(source));
    }).toList();
  }

  private Query awaitingApprovalQuery() {
    return new Query(Criteria.where("visibility").is("RESTRICTED")
        .and("approvedAt").is(null).and("declinedAt").is(null));
  }

  private Map<String, Long> countsBy(final String field) {
    final Map<String, Long> counts = new java.util.LinkedHashMap<>();
    for (Object value : mongoTemplate.findDistinct(
        new Query(), field, SchoolDocument.class, String.class)) {
      counts.put(String.valueOf(value),
          mongoTemplate.count(new Query(Criteria.where(field).is(value)), SchoolDocument.class));
    }
    return counts;
  }

  private Map<String, Long> eventCountsBy(final String field) {
    final Map<String, Long> counts = new java.util.LinkedHashMap<>();
    for (Object value : mongoTemplate.findDistinct(
        new Query(), field, SchoolEvent.class, String.class)) {
      counts.put(String.valueOf(value),
          mongoTemplate.count(new Query(Criteria.where(field).is(value)), SchoolEvent.class));
    }
    return counts;
  }

  /**
   * Spend and usage.
   *
   * @param windowDays the period the windowed figures cover
   * @param totalCostUsd every recorded call, all time
   * @param windowCostUsd cost within the window
   * @param costByKind cost within the window, split by what the call was for
   * @param distinctSessions conversations in the window. NOT people — the chat has no sign-in
   * @param distinctClients distinct salted client hashes, a rough visitor count
   * @param chatTurns how many questions were answered in the window
   * @param includesEstimates true when any row's tokens were estimated rather than reported
   */
  public record UsageResponse(
      int windowDays,
      double totalCostUsd,
      double windowCostUsd,
      Map<String, Double> costByKind,
      long distinctSessions,
      long distinctClients,
      long chatTurns,
      boolean includesEstimates) {
  }

  /**
   * A hyperlink found in a document, awaiting a decision.
   *
   * @param id the link id
   * @param sourceDocumentId the document it was found in
   * @param url the address
   * @param anchorText the link's visible text
   * @param likelyKind a guess at what is there, derived from the URL alone — never by asking
   *     the far end, since contacting it is the thing being approved
   * @param status what has been decided
   * @param failureReason why a fetch failed, if it did
   */
  public record LinkSummary(
      String id,
      String sourceDocumentId,
      String url,
      String anchorText,
      String likelyKind,
      String status,
      String failureReason) {

    static LinkSummary from(final SchoolLink link) {
      return new LinkSummary(link.id(), link.sourceDocumentId(), link.url(), link.anchorText(),
          link.likelyKind(), link.status().name(), link.failureReason());
    }
  }

  /** Corpus overview. */
  public record StatusResponse(
      Map<String, Long> documentsBySource,
      Map<String, Long> documentsByVisibility,
      long awaitingApproval,
      long totalEvents,
      Map<String, Long> eventsByType,
      List<SourceStatus> sources) {
  }

  /** Per-source ingest state. */
  public record SourceStatus(
      String source,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      String lastFailureReason,
      boolean running) {
  }

  /**
   * A page of results.
   *
   * @param items this page
   * @param total how many match the filter overall
   * @param page zero-based page number
   * @param size the page size used
   * @param <T> the row type
   */
  public record PageResponse<T>(List<T> items, long total, int page, int size) {
  }

  /** A bulk action. */
  public record BulkRequest(
      List<String> ids, String action, String approver, boolean force) {

    /** Normalises nulls so a malformed body cannot NPE the loop. */
    public BulkRequest {
      ids = ids == null ? List.of() : List.copyOf(ids);
      action = action == null ? "" : action;
      approver = approver == null || approver.isBlank() ? "admin" : approver;
    }
  }

  /** What a bulk action actually did. */
  public record BulkResponse(
      int approved, int declined, int revoked, int missing) {
  }

  /** One row in the documents table. */
  public record DocumentSummary(
      String id,
      String title,
      String preview,
      String sourceType,
      String sourceRef,
      Instant publishedAt,
      String visibility,
      String proposedVisibility,
      String proposalReason,
      String approvedBy,
      Instant approvedAt,
      boolean hasAttachment,
      String body,
      List<LinkSummary> discoveredLinks,
      String originalUrl) {

    static DocumentSummary from(final SchoolDocument document, final boolean hasAttachment,
        final List<LinkSummary> discoveredLinks) {
      final String body = document.body() == null ? "" : document.body();
      return new DocumentSummary(
          document.id(),
          document.title(),
          body.length() > PREVIEW_CHARS ? body.substring(0, PREVIEW_CHARS) + "…" : body,
          document.sourceType().name(),
          document.sourceRef(),
          document.publishedAt(),
          document.visibility().name(),
          document.proposedVisibility() == null ? null : document.proposedVisibility().name(),
          document.proposalReason(),
          document.approvedBy(),
          document.approvedAt(),
          hasAttachment,
          // The full text, so "show more" is a local expand rather than a round trip. These
          // are single-page letters, not books; the whole page is a few hundred KB at most.
          body,
          discoveredLinks,
          document.sourceRef() != null && document.sourceRef().startsWith("http")
              ? document.sourceRef()
              : null);
    }
  }
}
