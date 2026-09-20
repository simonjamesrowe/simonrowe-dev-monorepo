package com.simonrowe.coparent.messaging;

import com.simonrowe.coparent.shared.CoparentIds;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract for family messaging and permission decisions. */
@RestController
@RequestMapping("/api/coparent")
public class MessagingController {

  private final MessagingService service;

  public MessagingController(final MessagingService service) {
    this.service = service;
  }

  @GetMapping("/families/{familyId}/conversations")
  List<MessagingService.ConversationView> list(@PathVariable final String familyId) {
    return service.list(CoparentIds.parse(familyId));
  }

  @PostMapping("/families/{familyId}/conversations/message")
  @ResponseStatus(HttpStatus.CREATED)
  MessagingService.ConversationView createMessage(
      @PathVariable final String familyId,
      @RequestBody final CreateMessageRequest request) {
    return service.createMessage(CoparentIds.parse(familyId),
        optionalId(request.recipientId()), request.subject(), request.message());
  }

  @PostMapping("/families/{familyId}/conversations/permission")
  @ResponseStatus(HttpStatus.CREATED)
  MessagingService.ConversationView createPermission(
      @PathVariable final String familyId,
      @RequestBody final CreatePermissionRequest request) {
    return service.createPermission(CoparentIds.parse(familyId), request.subject(), request.type(),
        CoparentIds.parse(request.childId()), request.description());
  }

  @PostMapping("/conversations/{conversationId}/messages")
  @ResponseStatus(HttpStatus.CREATED)
  MessagingService.ConversationView sendMessage(
      @PathVariable final String conversationId,
      @RequestBody final SendMessageRequest request) {
    return service.sendMessage(CoparentIds.parse(conversationId), request.content());
  }

  @PostMapping("/conversations/{conversationId}/mark-read")
  MessagingService.ConversationView markRead(@PathVariable final String conversationId) {
    return service.markRead(CoparentIds.parse(conversationId));
  }

  @PostMapping("/conversations/{conversationId}/mark-unread")
  MessagingService.ConversationView markUnread(@PathVariable final String conversationId) {
    return service.markUnread(CoparentIds.parse(conversationId));
  }

  @PostMapping("/permissions/{permissionId}/approve")
  MessagingService.ConversationView approve(
      @PathVariable final String permissionId,
      @RequestBody(required = false) final PermissionDecisionRequest request) {
    return decide(permissionId, "approved", request);
  }

  @PostMapping("/permissions/{permissionId}/deny")
  MessagingService.ConversationView deny(
      @PathVariable final String permissionId,
      @RequestBody(required = false) final PermissionDecisionRequest request) {
    return decide(permissionId, "denied", request);
  }

  private MessagingService.ConversationView decide(
      final String permissionId,
      final String status,
      final PermissionDecisionRequest request) {
    return service.resolvePermission(CoparentIds.parse(permissionId), status,
        request == null ? null : request.response());
  }

  private static org.bson.types.ObjectId optionalId(final String value) {
    return value == null || value.isBlank() ? null : CoparentIds.parse(value);
  }

  record CreateMessageRequest(String recipientId, String subject, String message) {
  }

  record CreatePermissionRequest(
      String subject,
      String type,
      String childId,
      String description
  ) {
  }

  record SendMessageRequest(String content) {
  }

  record PermissionDecisionRequest(String response) {
  }
}
