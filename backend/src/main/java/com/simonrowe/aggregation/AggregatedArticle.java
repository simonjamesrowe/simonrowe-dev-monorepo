package com.simonrowe.aggregation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "aggregated_articles")
@CompoundIndex(
    name = "idx_visible_published",
    def = "{'visible': 1, 'publishedDate': -1}")
public record AggregatedArticle(
    @Id String id,
    String title,
    String sourceName,
    @Indexed String sourceUrl,
    @Indexed(unique = true) String originalUrl,
    String summary,
    String fullContent,
    String author,
    Instant publishedDate,
    Instant fetchedAt,
    boolean visible,
    String imageUrl
) {
}
