package com.simonrowe.admin;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "blogs")
@CompoundIndex(
    name = "idx_admin_published_created",
    def = "{'published': 1, 'createdAt': -1}")
public record Blog(
    @Id String id,
    String title,
    String shortDescription,
    String content,
    boolean published,
    String featuredImage,
    @Indexed List<String> tags,
    List<String> skills,
    Instant createdAt,
    Instant updatedAt,
    @Indexed(unique = true, sparse = true) String legacyId
) {
}
