package com.simonrowe.coparent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** A family message thread or formal permission request. */
@Document("conversations")
public record Conversation(
    @Id ObjectId id,
    ObjectId familyId,
    String type,
    String subject,
    ObjectId parent1Id,
    ObjectId parent2Id,
    List<Message> messages,
    PermissionRequest permissionRequest,
    Map<String, Integer> unreadCounts,
    Instant lastMessageAt,
    Instant deletedAt,
    Instant createdAt,
    Instant updatedAt
) {
  public Conversation {
    messages = messages == null ? List.of() : List.copyOf(messages);
    unreadCounts = unreadCounts == null ? Map.of() : Map.copyOf(unreadCounts);
  }

  public record Message(
      @Field("_id") ObjectId id,
      ObjectId senderId,
      String content,
      Instant timestamp,
      List<ObjectId> readBy
  ) {
    public Message {
      readBy = readBy == null ? List.of() : List.copyOf(readBy);
    }
  }

  public record PermissionRequest(
      @Field("_id") ObjectId id,
      String type,
      ObjectId childId,
      String childName,
      String description,
      ObjectId requestedBy,
      String status,
      Instant createdAt,
      Instant resolvedAt,
      String response
  ) {
  }
}
