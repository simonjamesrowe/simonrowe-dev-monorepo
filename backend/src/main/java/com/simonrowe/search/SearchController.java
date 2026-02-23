package com.simonrowe.search;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

  private final SearchService searchService;

  public SearchController(final SearchService searchService) {
    this.searchService = searchService;
  }

  @GetMapping
  public GroupedSearchResponse siteSearch(@RequestParam final String q) {
    return searchService.siteSearch(q);
  }

  @GetMapping("/blogs")
  public List<BlogSearchResult> blogSearch(@RequestParam final String q) {
    return searchService.blogSearch(q);
  }
}
