package com.simonrowe.coparent.document;

import com.simonrowe.coparent.model.DocumentSchoolOffer;
import com.simonrowe.coparent.model.DocumentSection;
import com.simonrowe.coparent.shared.CoparentIds;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP contract for "From school" ({@code specs/057-coparent-documents/contracts.md}). The
 * literal {@code school-offers} segment outranks {@code /documents/{documentId}}, so the two
 * controllers never compete for a path.
 */
@RestController
@RequestMapping("/api/coparent/families/{familyId}/documents/school-offers")
public class DocumentSchoolOfferController {

  private final DocumentSchoolOfferService service;

  public DocumentSchoolOfferController(final DocumentSchoolOfferService service) {
    this.service = service;
  }

  @GetMapping
  List<SchoolOfferResponse> pending(@PathVariable final String familyId) {
    return service.pending(CoparentIds.parse(familyId)).stream()
        .map(SchoolOfferResponse::from).toList();
  }

  @PostMapping("/{offerId}/keep")
  @ResponseStatus(HttpStatus.CREATED)
  DocumentController.DocumentResponse keep(
      @PathVariable final String familyId,
      @PathVariable final String offerId,
      @RequestBody(required = false) final KeepRequest request) {
    final KeepRequest body = request == null ? new KeepRequest(null, null, null) : request;
    final List<ObjectId> childIds = body.childIds() == null ? null
        : body.childIds().stream().map(CoparentIds::parse).toList();
    return DocumentController.DocumentResponse.from(service.keep(CoparentIds.parse(familyId),
        CoparentIds.parse(offerId),
        new DocumentSchoolOfferService.KeepValues(childIds, body.section(), body.title())));
  }

  @PostMapping("/{offerId}/dismiss")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void dismiss(@PathVariable final String familyId, @PathVariable final String offerId) {
    service.dismiss(CoparentIds.parse(familyId), CoparentIds.parse(offerId));
  }

  /** What a parent chose when keeping a letter. */
  public record KeepRequest(List<String> childIds, DocumentSection section, String title) {
  }

  /** An offer on the wire. */
  public record SchoolOfferResponse(
      String id,
      DocumentSchoolOffer.Kind kind,
      List<String> childIds,
      DocumentSection section,
      String title,
      String sender,
      Instant sentAt,
      boolean personal,
      String reason,
      String attachmentName,
      String reportLink,
      String suggestedTitle,
      LocalDate suggestedDatedOn
  ) {
    static SchoolOfferResponse from(final DocumentSchoolOffer offer) {
      return new SchoolOfferResponse(offer.id().toHexString(), offer.kind(),
          offer.childIds().stream().map(ObjectId::toHexString).toList(), offer.section(),
          offer.title(), offer.sender(), offer.sentAt(), offer.personal(), offer.reason(),
          offer.attachmentName(), offer.reportLink(), offer.suggestedTitle(),
          offer.suggestedDatedOn());
    }
  }
}
