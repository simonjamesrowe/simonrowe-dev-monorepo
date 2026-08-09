package com.simonrowe.favourites;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FavouriteRepository extends MongoRepository<Favourite, String> {

  List<Favourite> findByType(FavouriteType type);

  Page<Favourite> findByTypeOrderByCreatedAtDesc(FavouriteType type, Pageable pageable);

  boolean existsByTypeAndContentId(FavouriteType type, String contentId);

  void deleteByTypeAndContentId(FavouriteType type, String contentId);

  List<Favourite> findByTypeAndCreatedAtAfterOrderByCreatedAtDesc(
      FavouriteType type, Instant createdAt);

  /**
   * Favourites hearted inside an explicit window. Used by the backfill, which
   * generates one digest per historical week rather than one for "the last N
   * days"; the scheduled run uses the same method with {@code to} set to now.
   *
   * @param type the favourite type
   * @param from window start, inclusive
   * @param to window end, inclusive
   * @return matching favourites, most recently hearted first
   */
  List<Favourite> findByTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
      FavouriteType type, Instant from, Instant to);
}
