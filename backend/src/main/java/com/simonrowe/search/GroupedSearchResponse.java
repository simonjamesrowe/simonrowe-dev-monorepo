package com.simonrowe.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record GroupedSearchResponse(
    List<SearchResult> blogs,
    List<SearchResult> jobs,
    List<SearchResult> skills
) {
}
