package com.simonrowe.blog;

import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Validated
public class SearchController {

  private final BlogSearchService blogSearchService;

  public SearchController(final BlogSearchService blogSearchService) {
    this.blogSearchService = blogSearchService;
  }

  @GetMapping("/blogs")
  public List<BlogSearchResult> searchBlogs(
      @RequestParam @Size(min = 2, message = "Query must be at least 2 characters") final String q
  ) {
    return blogSearchService.search(q);
  }
}
