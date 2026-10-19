package com.simonrowe.factory.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PullRequestUrlTest {

  @Test
  void parsesPullRequestAddress() {
    assertThat(PullRequestUrl.parse("https://github.com/simonjamesrowe/agent-setup/pull/50"))
        .contains(new PullRequestUrl("simonjamesrowe", "agent-setup", 50));
  }

  @Test
  void ignoresQueryAndFragment() {
    assertThat(PullRequestUrl.parse("https://github.com/o/r/pull/7?x=1#discussion"))
        .contains(new PullRequestUrl("o", "r", 7));
  }

  /** Origin, not prefix: a string check would read the first two as GitHub. */
  @Test
  void rejectsLookalikesAndEverythingElse() {
    for (String url :
        List.of(
            "https://github.com.attacker.example/o/r/pull/1",
            "https://attacker.example/github.com/o/r/pull/1",
            "http://github.com/o/r/pull/1",
            "https://github.com:8443/o/r/pull/1",
            "https://github.com/o/r/pull/0",
            "https://github.com/o/r/pull/-3",
            "https://github.com/o/r/pull/x",
            "https://github.com/o/r/pull/1/files",
            "https://github.com/o/r/issues/1",
            "https://linear.app/x/issue/SIM-1",
            "https://factory.simonrowe.dev/fingerprint/abc",
            "not a url",
            "")) {
      assertThat(PullRequestUrl.parse(url)).as(url).isEmpty();
    }
    assertThat(PullRequestUrl.parse(null)).isEmpty();
  }

  @Test
  void canonicalFormNamesTheSamePullRequestTheSameWay() {
    assertThat(PullRequestUrl.parse("https://github.com/o/r/pull/7?x=1").orElseThrow().canonical())
        .isEqualTo("https://github.com/o/r/pull/7");
  }
}
