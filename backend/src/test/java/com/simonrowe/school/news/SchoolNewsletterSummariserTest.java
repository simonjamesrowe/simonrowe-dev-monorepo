package com.simonrowe.school.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolSourceType;
import com.simonrowe.school.model.Visibility;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SchoolNewsletterSummariserTest {

  private static final String MODEL = "summary-model";
  private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");

  private final Ai ai = mock(Ai.class);
  private final PromptRunner runner = mock(PromptRunner.class);
  private final SchoolUsageRecorder usage = mock(SchoolUsageRecorder.class);
  private final SchoolNewsletterSummariser summariser = new SchoolNewsletterSummariser(ai,
      new SchoolProperties(true, null, null, null, null, null, null, 0, MODEL, null, null, 0,
          null, null),
      usage, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setUp() {
    when(ai.withLlm(MODEL)).thenReturn(runner);
  }

  private static SchoolDocument newsletter(final String body) {
    return new SchoolDocument("doc-171", SchoolSourceType.WEBSITE_PAGE,
        "https://www.kilmorieschool.co.uk/parentportal/newsletter/?id=171",
        "Kilmorie Primary School Newsletter - 23rd October 2026", body,
        Instant.parse("2026-10-23T00:00:00Z"), NOW, Visibility.PUBLIC, null, null, null, null,
        false, List.of(), "hash-1", null);
  }

  @Test
  @DisplayName("stores the summary against the text it was written from")
  void tiesTheSummaryToTheText() {
    when(runner.createObjectIfPossible(anyString(), eq(SummarisedNewsletter.class)))
        .thenReturn(new SummarisedNewsletter("End of a busy half term", List.of(
            new SummarisedNewsletter.Point("INSET day Monday 2 November.", List.of()))));

    final NewsletterSummary summary = summariser.summarise(newsletter("text")).orElseThrow();

    assertThat(summary.id()).isEqualTo("doc-171");
    assertThat(summary.contentHash()).isEqualTo("hash-1");
    assertThat(summary.headline()).isEqualTo("End of a busy half term");
    assertThat(summary.points()).containsExactly(
        new NewsletterSummary.Point("INSET day Monday 2 November.", List.of()));
    assertThat(summary.model()).isEqualTo(MODEL);
    assertThat(summary.generatedAt()).isEqualTo(NOW);
    verify(usage).recordEstimated(eq(SchoolUsage.Kind.SUMMARISE), eq(MODEL), anyLong(),
        anyLong());
  }

  @Test
  @DisplayName("tells the model to leave children's names out")
  void asksForNoChildrensNames() {
    when(runner.createObjectIfPossible(anyString(), eq(SummarisedNewsletter.class)))
        .thenReturn(null);
    final ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

    summariser.summarise(newsletter("Stars of the week: ..."));

    verify(runner).createObjectIfPossible(prompt.capture(), eq(SummarisedNewsletter.class));
    assertThat(prompt.getValue())
        .contains("Never include a child's name")
        .contains("Published: 2026-10-23")
        .contains("Stars of the week: ...");
  }

  @Test
  @DisplayName("keeps only real year groups and at most six non-blank points")
  void sanitisesThePoints() {
    final List<SummarisedNewsletter.Point> points = new java.util.ArrayList<>();
    points.add(new SummarisedNewsletter.Point(" ", List.of()));
    points.add(new SummarisedNewsletter.Point("Pupil Parliament elections.",
        List.of("Year 2", "Year 9", "Nursery", "Year 6")));
    IntStream.range(0, 8).forEach(n -> points.add(
        new SummarisedNewsletter.Point("Point " + n, null)));

    final SummarisedNewsletter checked = SchoolNewsletterSummariser.checked(
        new SummarisedNewsletter("  Half term  ", points)).orElseThrow();

    assertThat(checked.headline()).isEqualTo("Half term");
    assertThat(checked.points()).hasSize(SchoolNewsletterSummariser.MAX_POINTS);
    assertThat(checked.points().get(0)).isEqualTo(new SummarisedNewsletter.Point(
        "Pupil Parliament elections.", List.of("Year 2", "Year 6")));
  }

  @Test
  @DisplayName("caps an overlong headline and point rather than storing them whole")
  void capsLengths() {
    final SummarisedNewsletter checked = SchoolNewsletterSummariser.checked(
        new SummarisedNewsletter("h".repeat(200), List.of(
            new SummarisedNewsletter.Point("p".repeat(1000), List.of())))).orElseThrow();

    assertThat(checked.headline()).hasSizeLessThanOrEqualTo(
        SchoolNewsletterSummariser.MAX_HEADLINE_CHARS).endsWith("…");
    assertThat(checked.points().get(0).text()).hasSizeLessThanOrEqualTo(
        SchoolNewsletterSummariser.MAX_POINT_CHARS);
  }

  @Test
  @DisplayName("a summary with no usable point is no summary")
  void noPointsNoSummary() {
    assertThat(SchoolNewsletterSummariser.checked(new SummarisedNewsletter("Headline",
        List.of(new SummarisedNewsletter.Point("", List.of()))))).isEmpty();
    assertThat(SchoolNewsletterSummariser.checked(null)).isEmpty();
  }

  @Test
  @DisplayName("a model failure leaves the newsletter unsummarised rather than failing the run")
  void survivesModelFailure() {
    when(runner.createObjectIfPossible(anyString(), eq(SummarisedNewsletter.class)))
        .thenThrow(new IllegalStateException("rate limited"));

    assertThat(summariser.summarise(newsletter("text"))).isEmpty();
  }

  @Test
  @DisplayName("makes no call for a newsletter with no text")
  void noTextNoCall() {
    assertThat(summariser.summarise(newsletter(" "))).isEmpty();

    verifyNoInteractions(ai);
  }
}
