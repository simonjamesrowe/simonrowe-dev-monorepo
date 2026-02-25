package com.simonrowe.admin;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "social_media_links")
public record SocialMediaLink(
    @Id String id,
    @Indexed String type,
    String link,
    String name,
    boolean includeOnResume,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {
}
