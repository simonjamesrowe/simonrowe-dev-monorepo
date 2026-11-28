package com.simonrowe.coparent.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.simonrowe.coparent.model.DocumentFile;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.model.DocumentVersion;
import com.simonrowe.coparent.model.DueKind;
import com.simonrowe.coparent.model.FamilyDocument;
import com.simonrowe.coparent.shared.CoparentIds;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP contract for the family document library ({@code specs/057-coparent-documents/
 * contracts.md}). Membership is checked by the services on every call.
 */
@RestController
@RequestMapping("/api/coparent/families/{familyId}/documents")
public class DocumentController {

  /**
   * Reads the multipart {@code details} part. It is taken as a string rather than bound by
   * content type, so it works whether the browser sends it as a JSON blob or a plain field.
   */
  private static final JsonMapper JSON = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .build();
  private static final int MAX_FILENAME = 80;

  private final DocumentService service;
  private final DocumentReadService reading;

  public DocumentController(final DocumentService service, final DocumentReadService reading) {
    this.service = service;
    this.reading = reading;
  }

  @GetMapping
  List<DocumentResponse> list(
      @PathVariable final String familyId,
      @RequestParam(defaultValue = "false") final boolean archived) {
    return service.list(CoparentIds.parse(familyId), archived).stream()
        .map(DocumentResponse::from).toList();
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  DocumentResponse create(
      @PathVariable final String familyId,
      @RequestPart("details") final String details,
      @RequestPart(name = "files", required = false) final List<MultipartFile> files) {
    final DetailsRequest request = parse(details, DetailsRequest.class);
    return DocumentResponse.from(service.create(CoparentIds.parse(familyId), request.values(),
        DocumentUploads.fromMultipart(files), optionalId(request.schoolOfferId())));
  }

  @PostMapping(path = "/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ProposalResponse read(
      @PathVariable final String familyId,
      @RequestPart(name = "files", required = false) final List<MultipartFile> files) {
    return ProposalResponse.from(reading.read(CoparentIds.parse(familyId),
        DocumentUploads.fromMultipart(files)));
  }

  @GetMapping("/{documentId}")
  DocumentResponse get(@PathVariable final String familyId,
      @PathVariable final String documentId) {
    final DocumentService.Detail detail = service.get(CoparentIds.parse(familyId),
        CoparentIds.parse(documentId));
    return DocumentResponse.from(detail.document(), detail.history());
  }

  @PutMapping("/{documentId}")
  DocumentResponse edit(
      @PathVariable final String familyId,
      @PathVariable final String documentId,
      @RequestBody final DetailsRequest request) {
    return DocumentResponse.from(service.edit(CoparentIds.parse(familyId),
        CoparentIds.parse(documentId), requireVersion(request.version()), request.values()));
  }

  @PostMapping(path = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  DocumentResponse addVersion(
      @PathVariable final String familyId,
      @PathVariable final String documentId,
      @RequestPart("details") final String details,
      @RequestPart(name = "files", required = false) final List<MultipartFile> files) {
    final VersionRequest request = parse(details, VersionRequest.class);
    return DocumentResponse.from(service.addVersion(CoparentIds.parse(familyId),
        CoparentIds.parse(documentId), requireVersion(request.version()),
        new DocumentService.VersionDates(request.datedOn(), request.dueOn(), request.dueKind()),
        DocumentUploads.fromMultipart(files)));
  }

  @PostMapping("/{documentId}/archive")
  DocumentResponse archive(
      @PathVariable final String familyId,
      @PathVariable final String documentId,
      @RequestBody final VersionOnly request) {
    return DocumentResponse.from(service.archive(CoparentIds.parse(familyId),
        CoparentIds.parse(documentId), requireVersion(request.version())));
  }

  @PostMapping("/{documentId}/restore")
  DocumentResponse restore(
      @PathVariable final String familyId,
      @PathVariable final String documentId,
      @RequestBody final VersionOnly request) {
    return DocumentResponse.from(service.restore(CoparentIds.parse(familyId),
        CoparentIds.parse(documentId), requireVersion(request.version())));
  }

  /**
   * Serves a file inline for the viewer, or as an attachment with {@code ?download=true}.
   * {@code nosniff} and a sandboxing CSP stop a browser treating the bytes as anything but the
   * type they were proven to be on upload. {@code Cache-Control} is set on the servlet response
   * itself, replacing the feature filter's {@code no-store} rather than adding a second header.
   * Downloads are not audited.
   */
  @GetMapping("/{documentId}/files/{fileId}")
  ResponseEntity<byte[]> file(
      @PathVariable final String familyId,
      @PathVariable final String documentId,
      @PathVariable final String fileId,
      @RequestParam(defaultValue = "false") final boolean download,
      final HttpServletResponse response) {
    final DocumentService.StoredFile stored = service.readFile(CoparentIds.parse(familyId),
        CoparentIds.parse(documentId), CoparentIds.parse(fileId));
    final ContentDisposition disposition = (download
        ? ContentDisposition.attachment() : ContentDisposition.inline())
        .filename(filename(stored), StandardCharsets.UTF_8)
        .build();
    response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(stored.file().mimeType()))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox")
        .body(stored.bytes());
  }

  /**
   * A download name from the title, kept to plain characters. Never used as a path, and
   * encoded by {@link ContentDisposition} besides.
   */
  static String filename(final DocumentService.StoredFile stored) {
    String base = stored.document().title() == null ? ""
        : stored.document().title().replaceAll("[^A-Za-z0-9 ._-]", "").strip();
    if (base.isEmpty()) {
      base = "document";
    }
    if (base.length() > MAX_FILENAME) {
      base = base.substring(0, MAX_FILENAME).strip();
    }
    final String version = stored.document().versions().size() > 1
        ? " v" + stored.versionNumber() : "";
    final String page = stored.pages() > 1 ? " page " + stored.page() : "";
    return base + version + page + "." + DocumentFileType.extension(stored.file().mimeType());
  }

  private static <T> T parse(final String json, final Class<T> type) {
    if (json == null || json.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The document's details are required");
    }
    try {
      return JSON.readValue(json, type);
    } catch (JacksonException malformed) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The document's details could not be read");
    }
  }

  private static long requireVersion(final Long version) {
    if (version == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "The version of the document you are changing is required");
    }
    return version;
  }

  private static ObjectId optionalId(final String value) {
    return value == null || value.isBlank() ? null : CoparentIds.parse(value);
  }

  // ---- wire types --------------------------------------------------------------------------

  /** The details a parent sets on create and edit. */
  public record DetailsRequest(
      List<String> childIds,
      DocumentSection section,
      String title,
      LocalDate datedOn,
      LocalDate dueOn,
      DueKind dueKind,
      String note,
      String schoolOfferId,
      Long version
  ) {
    DocumentService.DocumentValues values() {
      final List<ObjectId> ids = childIds == null ? List.of()
          : childIds.stream().map(CoparentIds::parse).toList();
      return new DocumentService.DocumentValues(ids, section, title, datedOn, dueOn, dueKind,
          note);
    }
  }

  /** The dates of a new version, at the version of the document being changed. */
  public record VersionRequest(LocalDate datedOn, LocalDate dueOn, DueKind dueKind,
      Long version) {
  }

  /** Just the version of the document being changed. */
  public record VersionOnly(Long version) {
  }

  /** A document on the wire. {@code history} is present only for a single document. */
  public record DocumentResponse(
      String id,
      String familyId,
      List<String> childIds,
      DocumentSection section,
      String title,
      LocalDate datedOn,
      LocalDate dueOn,
      DueKind dueKind,
      String note,
      List<VersionResponse> versions,
      SourceResponse source,
      Instant archivedAt,
      String archivedBy,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      long version,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<HistoryResponse> history
  ) {
    static DocumentResponse from(final FamilyDocument document) {
      return from(document, null);
    }

    static DocumentResponse from(final FamilyDocument document,
        final List<DocumentService.HistoryItem> history) {
      return new DocumentResponse(hex(document.id()), hex(document.familyId()),
          document.childIds().stream().map(DocumentController::hex).toList(),
          document.section(), document.title(), document.datedOn(), document.dueOn(),
          document.dueKind(), document.note(),
          document.versions().stream().map(VersionResponse::from).toList(),
          new SourceResponse(document.source().kind().name(),
              document.source().schoolDocumentId()),
          document.archivedAt(), hex(document.archivedBy()), hex(document.createdBy()),
          document.createdAt(), document.updatedAt(), document.version(),
          history == null ? null : history.stream().map(item -> new HistoryResponse(
              item.action(), hex(item.parentId()), item.at())).toList());
    }
  }

  /** One version on the wire. */
  public record VersionResponse(String versionId, List<FileResponse> files, LocalDate datedOn,
      LocalDate dueOn, DueKind dueKind, String uploadedBy, Instant uploadedAt) {
    static VersionResponse from(final DocumentVersion version) {
      return new VersionResponse(hex(version.versionId()),
          version.files().stream().map(FileResponse::from).toList(), version.datedOn(),
          version.dueOn(), version.dueKind(), hex(version.uploadedBy()), version.uploadedAt());
    }
  }

  /** One file's metadata on the wire. The bytes are fetched separately. */
  public record FileResponse(String fileId, String mimeType, long sizeBytes) {
    static FileResponse from(final DocumentFile file) {
      return new FileResponse(hex(file.fileId()), file.mimeType(), file.sizeBytes());
    }
  }

  /** Where a document came from. */
  public record SourceResponse(String kind, String schoolDocumentId) {
  }

  /** One line of history. */
  public record HistoryResponse(String action, String parentId, Instant at) {
  }

  /** The reader's suggestion for the add form. Every field may be null. */
  public record ProposalResponse(List<String> childIds, DocumentSection section, String title,
      LocalDate datedOn, LocalDate dueOn, DueKind dueKind, String confidence) {
    static ProposalResponse from(final DocumentReader.Proposal proposal) {
      return new ProposalResponse(proposal.childIds(), proposal.section(), proposal.title(),
          proposal.datedOn(), proposal.dueOn(), proposal.dueKind(), proposal.confidence());
    }
  }

  private static String hex(final ObjectId id) {
    return id == null ? null : id.toHexString();
  }
}
