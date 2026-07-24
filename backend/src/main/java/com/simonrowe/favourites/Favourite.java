package com.simonrowe.favourites;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A globally shared favourite over a piece of aggregated content, referenced by id only
 * ({@code contentId}); the referenced collections are unchanged. Favourites are not scoped
 * to a user — any authenticated user can add or remove one, and every visitor sees the same
 * set. Uniqueness per {@code (type, contentId)} is enforced by the index created in
 * {@code V014MakeFavouritesGlobal}; the {@code (type, createdAt)} index in the same change
 * unit covers the sorted listing query. These annotations are documentation only —
 * auto-index-creation is disabled, so the change unit is what actually creates the indexes.
 */
@Document(collection = "favourites")
@CompoundIndexes({
    @CompoundIndex(
        name = "idx_type_content",
        def = "{'type': 1, 'contentId': 1}",
        unique = true),
    @CompoundIndex(
        name = "idx_type_created",
        def = "{'type': 1, 'createdAt': -1}")
})
public record Favourite(
    @Id String id,
    FavouriteType type,
    String contentId,
    Instant createdAt
) {
}
