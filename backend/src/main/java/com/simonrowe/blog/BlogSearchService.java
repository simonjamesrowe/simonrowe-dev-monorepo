package com.simonrowe.blog;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;

@Service
public class BlogSearchService {

  private static final Pattern MARKDOWN_SYNTAX = Pattern.compile(
      "[#*_`~>\\[\\]()!|]|\\d+\\."
  );

  private final BlogSearchRepository blogSearchRepository;
  private final ElasticsearchOperations elasticsearchOperations;

  public BlogSearchService(
      final BlogSearchRepository blogSearchRepository,
      final ElasticsearchOperations elasticsearchOperations
  ) {
    this.blogSearchRepository = blogSearchRepository;
    this.elasticsearchOperations = elasticsearchOperations;
  }

  public List<BlogSearchResult> search(final String queryString) {
    String multiMatchQuery = String.format("""
        {
          "multi_match": {
            "query": "%s",
            "fields": ["title^3", "shortDescription^2", "content"],
            "type": "best_fields"
          }
        }
        """, queryString.replace("\"", "\\\""));

    Query query = new StringQuery(multiMatchQuery);
    return elasticsearchOperations.search(query, BlogSearchDocument.class).stream()
        .map(SearchHit::getContent)
        .map(BlogSearchResult::fromDocument)
        .toList();
  }

  public void indexBlog(final Blog blog) {
    List<String> tagNames = blog.tags() == null
        ? List.of()
        : blog.tags().stream().map(Tag::name).toList();

    List<String> skillNames = blog.skills() == null
        ? List.of()
        : blog.skills().stream().map(Skill::name).toList();

    String strippedContent = stripMarkdown(blog.content());

    BlogSearchDocument document = new BlogSearchDocument(
        blog.id(),
        blog.title(),
        blog.shortDescription(),
        strippedContent,
        tagNames,
        skillNames,
        blog.featuredImageUrl(),
        blog.createdDate()
    );

    blogSearchRepository.save(document);
  }

  public void deleteBlog(final String id) {
    blogSearchRepository.deleteById(id);
  }

  static String stripMarkdown(final String content) {
    if (content == null) {
      return "";
    }
    return MARKDOWN_SYNTAX.matcher(content).replaceAll(" ").replaceAll("\\s+", " ").trim();
  }
}
