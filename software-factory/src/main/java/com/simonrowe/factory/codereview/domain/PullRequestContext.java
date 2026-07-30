package com.simonrowe.factory.codereview.domain;

/** GitHub metadata required by the isolated review activity. */
public record PullRequestContext(
    String owner,
    String repository,
    int pullNumber,
    String title,
    String body,
    String cloneUrl,
    String baseSha,
    String headSha,
    Long installationId) {

  public String slug() {
    return owner + "/" + repository + "#" + pullNumber;
  }
}
