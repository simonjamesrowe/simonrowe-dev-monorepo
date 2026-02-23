package com.simonrowe.blog;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document(collection = "blogs")
@CompoundIndex(name = "idx_published_created", def = "{'published': 1, 'createdDate': -1}")
public record Blog(
    @Id String id,
    String title,
    String shortDescription,
    String content,
    boolean published,
    String featuredImageUrl,
    @Field("createdDate") Instant createdDate,
    @Field("updatedDate") Instant updatedDate,
    @DBRef List<Tag> tags,
    @DBRef List<Skill> skills
) {
}
