package com.simonrowe.blog;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/blogs")
@Validated
public class BlogController {

  private final BlogService blogService;

  public BlogController(final BlogService blogService) {
    this.blogService = blogService;
  }

  @GetMapping
  public List<BlogSummaryResponse> listPublishedBlogs() {
    return blogService.listPublished();
  }

  @GetMapping("/latest")
  public List<BlogSummaryResponse> getLatestBlogs(
      @RequestParam(defaultValue = "3") @Min(1) @Max(10) final int limit
  ) {
    return blogService.getLatest(limit);
  }

  @GetMapping("/{id}")
  public BlogDetailResponse getBlogById(@PathVariable final String id) {
    return blogService.getPublishedById(id);
  }
}
