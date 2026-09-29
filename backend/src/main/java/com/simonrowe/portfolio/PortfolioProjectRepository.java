package com.simonrowe.portfolio;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PortfolioProjectRepository extends MongoRepository<PortfolioProject, String> {

  List<PortfolioProject> findByPublishedTrueOrderByDisplayOrderAscNameAsc();

  List<PortfolioProject> findAllByOrderByDisplayOrderAscNameAsc();

  Optional<PortfolioProject> findBySlugAndPublishedTrue(String slug);
}
