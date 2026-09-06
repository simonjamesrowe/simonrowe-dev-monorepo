package com.simonrowe.factory.logwatch.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.simonrowe.factory.logwatch.domain.LogLine;
import com.simonrowe.factory.logwatch.domain.LogSignature;
import com.simonrowe.factory.logwatch.domain.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signature function carries the bulk of the test effort, because it is the feature.
 *
 * <p>Too strict and every line looks new forever, so the module is pure noise. Too loose and
 * unrelated faults merge, so a real problem hides inside one an operator has already accepted.
 *
 * <p>Fixtures are real lines from the production stack, captured via {@code docker logs} on the
 * Pi rather than from Loki, because Loki held nothing for the whole period this was written in.
 */
class SignatureExtractorTest {

  private static final Instant T0 = Instant.parse("2026-08-31T10:00:00Z");

  @Test
  @DisplayName("lines differing only by timestamp collapse to one signature")
  void timestampsAreInvariant() {
    String first = "ts=2026-08-31T10:34:52.95190745Z level=error msg=\"dropping data\"";
    String second = "ts=2026-08-31T11:41:02.00000001Z level=error msg=\"dropping data\"";

    assertThat(SignatureExtractor.normalise(first))
        .isEqualTo(SignatureExtractor.normalise(second));
  }

  @Test
  @DisplayName("nginx's common-log timestamp is normalised too, not just ISO-8601")
  void nginxTimestampsAreInvariant() {
    String first = "- - [31/Aug/2026:11:18:38 +0000] \"GET /a\" 500";
    String second = "- - [01/Sep/2026:03:02:01 +0000] \"GET /a\" 500";

    assertThat(SignatureExtractor.normalise(first))
        .isEqualTo(SignatureExtractor.normalise(second));
  }

  @Test
  void uuidsAreInvariant() {
    assertThat(SignatureExtractor.normalise("run 7f9817b7-1049-4b79-a568-4e85cd9c333f failed"))
        .isEqualTo(
            SignatureExtractor.normalise("run 11111111-2222-3333-4444-555555555555 failed"));
  }

  @Test
  void numbersAndQuantitiesAreInvariant() {
    assertThat(SignatureExtractor.normalise("ingest of 1311 lines totaling 457403 bytes"))
        .isEqualTo(SignatureExtractor.normalise("ingest of 42 lines totaling 99 bytes"));
    assertThat(SignatureExtractor.normalise("took 250ms"))
        .isEqualTo(SignatureExtractor.normalise("took 1300ms"));
  }

  @Test
  void pathsAndAddressesAreInvariant() {
    assertThat(SignatureExtractor.normalise("cannot open \"/var/lib/alloy/data/positions.yml\""))
        .isEqualTo(SignatureExtractor.normalise("cannot open \"/etc/docker/daemon.json\""));
    assertThat(SignatureExtractor.normalise("upstream 172.18.0.14:8080 timed out"))
        .isEqualTo(SignatureExtractor.normalise("upstream 10.0.0.2:9200 timed out"));
  }

  @Test
  void hexIdentifiersAreInvariant() {
    assertThat(SignatureExtractor.normalise("container 8e71fea3ee962ce288be exited"))
        .isEqualTo(SignatureExtractor.normalise("container d7db3b46666d4ba161b1 exited"));
  }

  @Test
  @DisplayName("terminal control codes do not split a token the later rules must still match")
  void ansiCodesAreStripped() {
    // Built from a real escape char rather than pasted, so the fixture survives an editor,
    // a diff viewer or a copy-paste that would silently eat a raw control byte.
    String esc = String.valueOf((char) 27);
    String coloured = esc + "[31mERROR" + esc + "[0m at 2026-08-31T10:00:00Z";
    String plain = "ERROR at 2026-08-31T11:00:00Z";

    assertThat(SignatureExtractor.normalise(coloured))
        .isEqualTo(SignatureExtractor.normalise(plain));
  }

  /**
   * The other half of the contract, and the easier one to lose while loosening the rules above.
   *
   * <p>If these collapsed, a new exception would hide inside a signature an operator had already
   * cancelled, and the module would report all-clear while the fault ran.
   */
  @Test
  @DisplayName("genuinely different problems keep different signatures")
  void differentProblemsDoNotMerge() {
    String sendFailure = "level=error msg=\"final error sending batch\" status=429";
    String connectionFailure = "level=error msg=\"connection refused\" status=429";

    assertThat(SignatureExtractor.normalise(sendFailure))
        .isNotEqualTo(SignatureExtractor.normalise(connectionFailure));
  }

  @Test
  @DisplayName("a varying status code does collapse, and that is the intended trade")
  void statusCodesCollapseIntoOneProblem() {
    String quota = "level=error msg=\"final error sending batch\" status=429";
    String auth = "level=error msg=\"final error sending batch\" status=401";

    // Both are bare numbers, so they normalise together. Deliberate: "the send failed with a
    // status" is one problem whose status varies, and the ticket body carries the real code in
    // its example line. Splitting on it would file a fresh ticket for every status a flapping
    // upstream returns.
    assertThat(SignatureExtractor.normalise(quota)).isEqualTo(SignatureExtractor.normalise(auth));
  }

  @Test
  @DisplayName("the level word is part of the signature, so a WARN turning ERROR is a new problem")
  void severityWordSeparatesSignatures() {
    assertThat(SignatureExtractor.normalise("WARN slow query 100ms"))
        .isNotEqualTo(SignatureExtractor.normalise("ERROR slow query 100ms"));
  }

  @Test
  void shortHexLikeWordsAreNotSwallowed() {
    assertThat(SignatureExtractor.normalise("the feed is dead")).contains("feed");
    assertThat(SignatureExtractor.normalise("decode failed")).contains("decode");
  }

  @Test
  @DisplayName("grouping is per (container, signature) - one fault per place to look")
  void groupsPerContainer() {
    List<LogLine> lines =
        List.of(
            new LogLine("backend", T0, Severity.ERROR, "level=error msg=\"boom\" id=1"),
            new LogLine(
                "backend", T0.plusSeconds(60), Severity.ERROR, "level=error msg=\"boom\" id=2"),
            new LogLine("nginx", T0, Severity.ERROR, "level=error msg=\"boom\" id=3"));

    List<LogSignature> grouped = SignatureExtractor.group(lines);

    assertThat(grouped).hasSize(2);
    assertThat(grouped)
        .extracting(LogSignature::container, LogSignature::occurrences)
        .containsExactly(tuple("backend", 2), tuple("nginx", 1));
  }

  @Test
  void groupTracksTimeRangeAndKeepsRealExample() {
    // Both lines carry the same level, so they normalise together. The level word IS part of the
    // signature - "ERROR slow query" and "WARN slow query" are deliberately two problems, because
    // a message that has started erroring rather than warning is a change worth a separate ticket.
    List<LogLine> lines =
        List.of(
            new LogLine("backend", T0.plusSeconds(600), Severity.WARN, "WARN slow query 900ms"),
            new LogLine("backend", T0, Severity.WARN, "WARN slow query 100ms"));

    LogSignature only = SignatureExtractor.group(lines).getFirst();

    assertThat(only.firstSeen()).isEqualTo(T0);
    assertThat(only.lastSeen()).isEqualTo(T0.plusSeconds(600));
    assertThat(only.occurrences()).isEqualTo(2);
    assertThat(only.exampleLine()).isEqualTo("WARN slow query 900ms");
  }

  @Test
  @DisplayName("the cap drops the least severe and least frequent, never the worst")
  void capOrderingIsMostSevereFirst() {
    LogSignature manyWarns =
        new LogSignature(
            "w", Severity.WARN, "c", 900, T0, T0, "w",
            "logger:w", List.of(new LogSignature.Variant("w", 900, "w")), 1);
    LogSignature oneError =
        new LogSignature(
            "e", Severity.ERROR, "c", 1, T0, T0, "e",
            "logger:e", List.of(new LogSignature.Variant("e", 1, "e")), 1);
    LogSignature manyErrors =
        new LogSignature(
            "e2", Severity.ERROR, "c", 50, T0, T0, "e2",
            "logger:e2", List.of(new LogSignature.Variant("e2", 50, "e2")), 1);

    List<LogSignature> sorted = new ArrayList<>(List.of(manyWarns, oneError, manyErrors));
    sorted.sort(LogSignature.MOST_SEVERE_FIRST);

    assertThat(sorted).containsExactly(manyErrors, oneError, manyWarns);
  }

  @Test
  void normaliseToleratesNull() {
    assertThat(SignatureExtractor.normalise(null)).isEmpty();
  }

  private static LogLine line(final String container, final Severity severity, final String raw) {
    return new LogLine(container, T0, severity, raw);
  }

  @Test
  @DisplayName("SIM-13/24/25: three phrasings from one logger become one group")
  void oneLoggerIsOneGroup() {
    String prefix =
        "{\"@timestamp\":\"2026-09-05T08:18:45.175457758Z\",\"log\":{\"level\":\"ERROR\","
            + "\"logger\":\"com.embabel.agent.spi.validation.DefaultAgentValidationManager\"},"
            + "\"message\":\"";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(
                line("backend", Severity.ERROR, prefix + "Validation failed with 1 errors:\"}"),
                line("backend", Severity.ERROR, prefix
                    + "- MISSING_GOALS: Agent 'ContentAggregation' must have one goal\"}"),
                line("backend", Severity.ERROR, prefix
                    + "- MISSING_GOALS: Agent 'WeeklyDigest' must have one goal\"}")));

    assertThat(grouped).hasSize(1);
    assertThat(grouped.getFirst().occurrences()).isEqualTo(3);
    assertThat(grouped.getFirst().sourceKey())
        .isEqualTo("logger:com.embabel.agent.spi.validation.DefaultAgentValidationManager");
    assertThat(grouped.getFirst().distinctVariants()).isEqualTo(3);
    assertThat(grouped.getFirst().variants()).hasSize(3);
  }

  @Test
  @DisplayName("SIM-16/23: one Alloy component with two different error payloads is one group")
  void oneAlloyComponentIsOneGroup() {
    String prefix =
        "ts=2026-09-03T05:59:25.342351851Z level=error msg=\"final error sending batch\" "
            + "component_id=loki.write.grafana_cloud error=\"";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(
                line("alloy", Severity.ERROR, prefix + "dial tcp: server misbehaving\""),
                line("alloy", Severity.ERROR, prefix + "server returned HTTP status 400\"")));

    assertThat(grouped).hasSize(1);
    assertThat(grouped.getFirst().sourceKey()).isEqualTo("logger:loki.write.grafana_cloud");
    assertThat(grouped.getFirst().distinctVariants()).isEqualTo(2);
  }

  @Test
  @DisplayName("a line with no identifiable source still groups on its normalised text")
  void unrecognisedFormatFallsBackToTheSignature() {
    String raw = "ERROR: Elasticsearch did not exit normally - check the logs at /var/log/es.log";
    List<LogSignature> grouped =
        SignatureExtractor.group(List.of(line("elasticsearch", Severity.ERROR, raw)));

    assertThat(grouped).hasSize(1);
    assertThat(grouped.getFirst().sourceKey())
        .isEqualTo("line:" + SignatureExtractor.normalise(raw));
  }

  @Test
  @DisplayName("severity is part of the group key, so WARN and ERROR never merge")
  void severitySplitsGroups() {
    String raw = "{\"log\":{\"logger\":\"com.example.Thing\"},\"message\":\"slow query\"}";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(line("backend", Severity.ERROR, raw), line("backend", Severity.WARN, raw)));

    assertThat(grouped).hasSize(2);
  }

  @Test
  @DisplayName("the same source in two containers stays two problems")
  void containerStillSplitsGroups() {
    String raw = "{\"log\":{\"logger\":\"com.example.Thing\"},\"message\":\"boom\"}";
    List<LogSignature> grouped =
        SignatureExtractor.group(
            List.of(line("backend", Severity.ERROR, raw), line("deployer", Severity.ERROR, raw)));

    assertThat(grouped).hasSize(2);
  }

  @Test
  @DisplayName("variants are most-frequent-first and capped, with the true total kept")
  void variantsAreOrderedAndCapped() {
    String prefix = "{\"log\":{\"logger\":\"com.example.Chatty\"},\"message\":\"variant ";
    List<LogLine> lines = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      lines.add(line("backend", Severity.ERROR, prefix + (char) ('a' + i) + "\"}"));
    }
    // Make one variant clearly dominant so the ordering assertion is not a tie-break.
    lines.add(line("backend", Severity.ERROR, prefix + "a\"}"));
    lines.add(line("backend", Severity.ERROR, prefix + "a\"}"));

    LogSignature only = SignatureExtractor.group(lines).getFirst();

    assertThat(only.distinctVariants()).isEqualTo(8);
    assertThat(only.variants()).hasSize(LogSignature.MAX_VARIANTS);
    assertThat(only.variants().getFirst().occurrences()).isEqualTo(3);
    assertThat(only.variants().getFirst().signature()).contains("variant a");
    assertThat(only.signature()).isEqualTo(only.variants().getFirst().signature());
  }
}
