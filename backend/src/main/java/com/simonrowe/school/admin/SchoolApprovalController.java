package com.simonrowe.school.admin;

import com.simonrowe.school.model.SchoolDocument;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin approval queue for Term Time.
 *
 * <p>Sits under {@code /api/admin/**}, which {@code SecurityConfig} gates on the site admin role.
 * That is deliberately a <b>different</b> role from the one granting the restricted chat tier:
 * approving what the world can see is an editorial act, while reading the mailbox is not, and the
 * two should not be the same grant.
 *
 * <p>Responses carry a preview rather than the full body. The queue is a list view, and shipping
 * whole newsletters into it makes the page heavy for no benefit — but note the preview is of
 * content that has not yet been approved, so the endpoint is admin-gated for that reason too.
 */
@RestController
@RequestMapping("/api/admin/school")
public class SchoolApprovalController {

  private static final int PREVIEW_CHARS = 400;

  private final SchoolApprovalService approvalService;

  public SchoolApprovalController(final SchoolApprovalService approvalService) {
    this.approvalService = approvalService;
  }

  /**
   * Documents awaiting a decision.
   *
   * @return the queue
   */
  @GetMapping("/approvals")
  public List<ApprovalItem> queue() {
    return approvalService.queue().stream().map(ApprovalItem::from).toList();
  }

  /**
   * Promotes a document to the public tier.
   *
   * @param id the document id
   * @param jwt the approving admin
   * @return the updated item, or 404
   */
  @PostMapping("/approvals/{id}/approve")
  public ResponseEntity<ApprovalItem> approve(
      @PathVariable final String id, @AuthenticationPrincipal final Jwt jwt) {
    final String approver = jwt == null ? "unknown" : jwt.getSubject();
    return approvalService.approve(id, approver)
        .map(ApprovalItem::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Declines a proposal, leaving the document restricted.
   *
   * @param id the document id
   * @return the updated item, or 404
   */
  @PostMapping("/approvals/{id}/decline")
  public ResponseEntity<ApprovalItem> decline(@PathVariable final String id) {
    return approvalService.decline(id)
        .map(ApprovalItem::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Takes a document back out of the public tier.
   *
   * @param id the document id
   * @return the updated item, or 404
   */
  @PostMapping("/approvals/{id}/revoke")
  public ResponseEntity<ApprovalItem> revoke(@PathVariable final String id) {
    return approvalService.revoke(id)
        .map(ApprovalItem::from)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * One row in the queue.
   *
   * @param id the document id
   * @param title its title
   * @param preview the first few hundred characters, enough to judge it by
   * @param sourceType where it came from
   * @param publishedAt when the source published it
   * @param visibility its current tier
   * @param proposalReason why the classifier proposed promotion
   * @param nameGateBlocked whether a non-staff name blocks it regardless of any decision
   */
  public record ApprovalItem(
      String id,
      String title,
      String preview,
      String sourceType,
      Instant publishedAt,
      String visibility,
      String proposalReason,
      boolean nameGateBlocked) {

    static ApprovalItem from(final SchoolDocument document) {
      final String body = document.body() == null ? "" : document.body();
      return new ApprovalItem(
          document.id(),
          document.title(),
          body.length() > PREVIEW_CHARS ? body.substring(0, PREVIEW_CHARS) + "…" : body,
          document.sourceType().name(),
          document.publishedAt(),
          document.visibility().name(),
          document.proposalReason(),
          document.nameGateBlocked());
    }
  }
}
