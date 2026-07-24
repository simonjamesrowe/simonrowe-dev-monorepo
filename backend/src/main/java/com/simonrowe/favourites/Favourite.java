package com.simonrowe.favourites;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A record that a user saved a piece of aggregated content as a favourite. Content is
 * referenced by id only ({@code contentId}); the referenced collections are unchanged.
 * Uniqueness per {@code (userId, type, contentId)} is enforced by the index created in
 * {@code V013CreateFavouritesUniqueIndex}.
 */
@Document(collection = "favourites")
@CompoundIndex(
    name = "idx_user_type_content",
    def = "{'userId': 1, 'type': 1, 'contentId': 1}",
    unique = true)
public record Favourite(
    @Id String id,
    String userId,
    FavouriteType type,
    String contentId,
    Instant createdAt
) {
}
