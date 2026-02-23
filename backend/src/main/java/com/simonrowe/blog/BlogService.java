package com.simonrowe.blog;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BlogService {

  private final BlogRepository blogRepository;

  public BlogService(final BlogRepository blogRepository) {
    this.blogRepository = blogRepository;
  }

  public List<BlogSummaryResponse> listPublished() {
    return blogRepository.findByPublishedTrueOrderByCreatedDateDesc().stream()
        .map(BlogSummaryResponse::fromEntity)
        .toList();
  }

  public BlogDetailResponse getPublishedById(final String id) {
    return blogRepository.findByIdAndPublishedTrue(id)
        .map(BlogDetailResponse::fromEntity)
        .orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Blog post not found"));
  }

  public List<BlogSummaryResponse> getLatest(final int limit) {
    return blogRepository.findByPublishedTrueOrderByCreatedDateDesc().stream()
        .limit(limit)
        .map(BlogSummaryResponse::fromEntity)
        .toList();
  }
}
