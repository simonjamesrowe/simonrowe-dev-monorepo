package com.simonrowe.favourites;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface FavouriteRepository extends MongoRepository<Favourite, String> {

  List<Favourite> findByType(FavouriteType type);

  Page<Favourite> findByTypeOrderByCreatedAtDesc(FavouriteType type, Pageable pageable);

  boolean existsByTypeAndContentId(FavouriteType type, String contentId);

  void deleteByTypeAndContentId(FavouriteType type, String contentId);
}
