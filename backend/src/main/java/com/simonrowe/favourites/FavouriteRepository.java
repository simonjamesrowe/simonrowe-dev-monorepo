package com.simonrowe.favourites;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FavouriteRepository extends MongoRepository<Favourite, String> {

  List<Favourite> findByUserIdAndType(String userId, FavouriteType type);

  Page<Favourite> findByUserIdAndTypeOrderByCreatedAtDesc(
      String userId, FavouriteType type, Pageable pageable);

  boolean existsByUserIdAndTypeAndContentId(
      String userId, FavouriteType type, String contentId);

  void deleteByUserIdAndTypeAndContentId(
      String userId, FavouriteType type, String contentId);
}
