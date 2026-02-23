package com.simonrowe.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class BlogServiceTest {

  @Mock
  private BlogRepository blogRepository;

  @InjectMocks
  private BlogService blogService;

  @Test
  void listPublishedReturnsMappedSummaries() {
    Blog blog = sampleBlog("b-1", "Spring Boot Tips", true);
    given(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).willReturn(List.of(blog));

    List<BlogSummaryResponse> result = blogService.listPublished();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).id()).isEqualTo("b-1");
    assertThat(result.get(0).title()).isEqualTo("Spring Boot Tips");
    assertThat(result.get(0).tags()).isEmpty();
    assertThat(result.get(0).skills()).isEmpty();
  }

  @Test
  void getPublishedByIdReturnsDetailWhenFound() {
    Blog blog = sampleBlog("b-2", "Kubernetes Deep Dive", true);
    given(blogRepository.findByIdAndPublishedTrue("b-2")).willReturn(Optional.of(blog));

    BlogDetailResponse result = blogService.getPublishedById("b-2");

    assertThat(result.id()).isEqualTo("b-2");
    assertThat(result.title()).isEqualTo("Kubernetes Deep Dive");
    assertThat(result.content()).isEqualTo("Full article content here.");
  }

  @Test
  void getPublishedByIdThrowsNotFoundWhenMissing() {
    given(blogRepository.findByIdAndPublishedTrue("missing")).willReturn(Optional.empty());

    assertThatThrownBy(() -> blogService.getPublishedById("missing"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException responseException = (ResponseStatusException) ex;
          assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
          assertThat(responseException.getReason()).isEqualTo("Blog post not found");
        });
  }

  @Test
  void getLatestReturnsLimitedPublishedBlogs() {
    List<Blog> blogs = List.of(
        sampleBlog("b-1", "Post 1", true),
        sampleBlog("b-2", "Post 2", true),
        sampleBlog("b-3", "Post 3", true),
        sampleBlog("b-4", "Post 4", true)
    );
    given(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).willReturn(blogs);

    List<BlogSummaryResponse> result = blogService.getLatest(3);

    assertThat(result).hasSize(3);
    assertThat(result.get(0).id()).isEqualTo("b-1");
    assertThat(result.get(2).id()).isEqualTo("b-3");
  }

  @Test
  void listPublishedWithTagsMapsTagNames() {
    Tag tag = new Tag("t-1", "Kubernetes");
    Blog blog = new Blog("b-1", "Post", "Short", "Content", true, null,
        Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-01T00:00:00Z"),
        List.of(tag), List.of());
    given(blogRepository.findByPublishedTrueOrderByCreatedDateDesc()).willReturn(List.of(blog));

    List<BlogSummaryResponse> result = blogService.listPublished();

    assertThat(result.get(0).tags()).hasSize(1);
    assertThat(result.get(0).tags().get(0).name()).isEqualTo("Kubernetes");
  }

  private static Blog sampleBlog(final String id, final String title, final boolean published) {
    return new Blog(
        id,
        title,
        "Short description of " + title,
        "Full article content here.",
        published,
        "/images/blogs/sample.jpg",
        Instant.parse("2024-06-01T10:00:00Z"),
        Instant.parse("2024-06-01T10:00:00Z"),
        List.of(),
        List.of()
    );
  }
}
