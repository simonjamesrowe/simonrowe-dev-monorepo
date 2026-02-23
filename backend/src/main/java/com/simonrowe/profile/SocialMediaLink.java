package com.simonrowe.profile;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "social_medias")
public record SocialMediaLink(
    @Id String id,
    String type,
    String name,
    String link,
    Boolean includeOnResume,
    Instant createdAt,
    Instant updatedAt
) {
}
