package com.simonrowe.reviewer.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simonrowe.reviewer.domain.PullRequestContext;
import com.simonrowe.reviewer.domain.ReviewRequest;
import org.junit.jupiter.api.Test;

class GitHubGatewayTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void usesBaseRepositoryCloneUrlBecauseItOwnsThePullRequestRef() throws Exception {
    JsonNode payload =
        objectMapper.readTree(
            """
            {
              "title": "Review me",
              "body": "Description",
              "head": {
                "sha": "head-sha",
                "repo": {"clone_url": "https://github.com/contributor/fork.git"}
              },
              "base": {
                "sha": "base-sha",
                "repo": {"clone_url": "https://github.com/example/project.git"}
              }
            }
            """);

    PullRequestContext result =
        GitHubGateway.toPullRequestContext(
            new ReviewRequest("example", "project", 42, "head-sha", 123L, false), payload);

    assertThat(result.cloneUrl()).isEqualTo("https://github.com/example/project.git");
    assertThat(result.baseSha()).isEqualTo("base-sha");
    assertThat(result.headSha()).isEqualTo("head-sha");
    assertThat(result.installationId()).isEqualTo(123L);
  }
}
