package com.simonrowe.factory.ticketagent.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TicketAgentGitHubTest {

  @Test
  void keepsTheTailAndEarlierLinesThatNameFailure() {
    List<String> log = new ArrayList<>();
    log.add("2026-10-19T12:00:00.0000000Z > Task :backend:compileJava");
    log.add("2026-10-19T12:00:01.0000000Z ChatServiceTest > answers() FAILED");
    IntStream.range(0, 500).forEach(index -> log.add("noise " + index));
    log.add("2026-10-19T12:09:00.0000000Z BUILD FAILED in 9m");

    String summary = TicketAgentGitHub.summariseLog(log.iterator(), 50);

    assertThat(summary)
        .contains("Earlier lines naming a failure:\nChatServiceTest > answers() FAILED")
        .contains("Last 50 lines:")
        .contains("BUILD FAILED in 9m")
        .doesNotContain("noise 0\n")
        .doesNotContain("2026-10-19T12:09:00");
  }

  @Test
  void shortLogIsKeptWhole() {
    String summary = TicketAgentGitHub.summariseLog(List.of("a", "b").iterator(), 50);

    assertThat(summary).isEqualTo("Last 2 lines:\na\nb\n");
  }

  /** Unbounded input: memory and time stay flat however long the log is. */
  @Test
  void hugeLogIsSummarisedInOnePass() {
    var lines = IntStream.range(0, 2_000_000).mapToObj(index -> "line " + index).iterator();
    long start = System.nanoTime();

    String summary = TicketAgentGitHub.summariseLog(lines, 200);

    assertThat(summary).contains("line 1999999").doesNotContain("line 1799799\n");
    assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
  }

  /** The log redirect is followed only to GitHub's log storage, by parsed host. */
  @Test
  void followsOnlyHttpsRedirectsToGitHubsLogStorage() {
    assertThat(TicketAgentGitHub.logLocation(
        "https://productionresultssa1.blob.core.windows.net/actions-results/x?sig=1"))
        .isPresent();
    assertThat(TicketAgentGitHub.logLocation(
        "https://pipelinesghubeus2.actions.githubusercontent.com/logs/x")).isPresent();
    for (String location :
        List.of(
            "http://productionresultssa1.blob.core.windows.net/x",
            "https://blob.core.windows.net.attacker.example/x",
            "https://attacker.example/actions.githubusercontent.com",
            "https://169.254.169.254/latest/meta-data",
            "not a url")) {
      assertThat(TicketAgentGitHub.logLocation(location)).as(location).isEmpty();
    }
  }
}
