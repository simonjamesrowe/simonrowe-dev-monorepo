package com.simonrowe.school.judge;

import com.simonrowe.school.judge.SchoolJudgement.Outcome;
import com.simonrowe.school.model.SchoolDocument;
import com.simonrowe.school.model.SchoolDocumentRepository;
import com.simonrowe.school.model.Visibility;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin view of the publication judge: what it decided, why, and whether the owner has since
 * overruled it.
 *
 * <p>Under {@code /api/admin/**}, which {@code SecurityConfig} gates on the site admin role. Each
 * item carries a preview of a school email the judge may have kept private, which is the other
 * reason it must stay there.
 */
@RestController
@RequestMapping("/api/admin/school/judge")
public class SchoolJudgeController {

  private static final int PREVIEW_CHARS = 300;
  private static final Duration SUMMARY_WINDOW = Duration.ofDays(30);
  private static final String DISCLAIMER = "The information contained within this e-mail";

  private final PublicationJudgeService service;
  private final SchoolJudgementRepository judgements;
  private final SchoolDocumentRepository documents;
  private final SchoolJudgeProperties properties;
  private final Clock clock;

  public SchoolJudgeController(final PublicationJudgeService service,
      final SchoolJudgementRepository judgements, final SchoolDocumentRepository documents,
      final SchoolJudgeProperties properties, final Clock clock) {
    this.service = service;
    this.judgements = judgements;
    this.documents = documents;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * What the judge has decided recently.
   *
   * @return the mode, 30-day counts and the latest 200 judgements
   */
  @GetMapping
  public Summary summary() {
    final List<SchoolJudgement> recent = judgements.findTop200ByOrderByJudgedAtDesc();
    final Map<String, SchoolDocument> current = documents
        .findAllById(recent.stream().map(SchoolJudgement::id).toList()).stream()
        .collect(Collectors.toMap(SchoolDocument::id, Function.identity()));
    final List<Item> items = recent.stream()
        .map(judgement -> Item.of(judgement, current.get(judgement.id())))
        .toList();

    final Instant since = clock.instant().minus(SUMMARY_WINDOW);
    final Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
    for (Outcome outcome : Outcome.values()) {
      counts.put(outcome, 0);
    }
    int overridden = 0;
    for (Item item : items) {
      if (item.judgedAt().isBefore(since)) {
        continue;
      }
      counts.merge(item.outcome(), 1, Integer::sum);
      if (item.overridden()) {
        overridden++;
      }
    }
    return new Summary(properties.mode().name(), properties.model(), PublicationJudge.VERSION,
        counts.get(Outcome.APPROVED), counts.get(Outcome.DECLINED), counts.get(Outcome.QUEUED),
        counts.get(Outcome.SHADOW), overridden, items);
  }

  /**
   * Runs the judge now over everything waiting, rather than after the next mail sync.
   *
   * @return what it did
   */
  @PostMapping("/run")
  public PublicationJudgeService.SweepResult run() {
    return service.sweep();
  }

  /**
   * The page's data.
   *
   * @param mode OFF, SHADOW or AUTO
   * @param model the model the judge asks
   * @param version the prompt version
   * @param approved published in the last 30 days
   * @param declined kept private in the last 30 days
   * @param queued left for the owner in the last 30 days
   * @param shadow recorded without deciding in the last 30 days
   * @param overridden decisions in the last 30 days the owner has since reversed
   * @param items the latest judgements, newest first
   */
  public record Summary(String mode, String model, String version, int approved, int declined,
      int queued, int shadow, int overridden, List<Item> items) {
  }

  /**
   * One judgement beside the document's state now.
   *
   * @param id the document id, which the approval endpoints take
   * @param title the title
   * @param sourceType EMAIL or PDF
   * @param publishedAt when the school sent it
   * @param verdict PUBLIC, PRIVATE or UNSURE
   * @param confidence 0 to 1
   * @param reason why
   * @param rule the rule that decided it, or null for the model
   * @param followsDocumentId the email an attachment followed, or null
   * @param senderAuthenticated whether Google verified the sender
   * @param outcome what the judge did
   * @param judgedAt when
   * @param currentState PUBLIC, PRIVATE, WAITING, or MISSING when the document is gone
   * @param overridden true when the owner has since reversed the judge's decision
   * @param preview the start of the text, footer removed
   */
  public record Item(String id, String title, String sourceType, Instant publishedAt,
      String verdict, double confidence, String reason, String rule, String followsDocumentId,
      boolean senderAuthenticated, Outcome outcome, Instant judgedAt, String currentState,
      boolean overridden, String preview) {

    static Item of(final SchoolJudgement judgement, final SchoolDocument document) {
      final String state = stateOf(document);
      final boolean overridden =
          judgement.outcome() == Outcome.APPROVED && !"PUBLIC".equals(state)
          || judgement.outcome() == Outcome.DECLINED && !"PRIVATE".equals(state);
      return new Item(judgement.id(),
          document == null ? judgement.title() : document.title(),
          judgement.sourceType() == null ? null : judgement.sourceType().name(),
          judgement.publishedAt(), judgement.verdict().name(), judgement.confidence(),
          judgement.reason(), judgement.rule(), judgement.followsDocumentId(),
          judgement.senderAuthenticated(), judgement.outcome(), judgement.judgedAt(), state,
          overridden && document != null, preview(document));
    }

    private static String stateOf(final SchoolDocument document) {
      if (document == null) {
        return "MISSING";
      }
      if (document.visibility() == Visibility.PUBLIC) {
        return "PUBLIC";
      }
      return document.declinedAt() != null ? "PRIVATE" : "WAITING";
    }

    private static String preview(final SchoolDocument document) {
      if (document == null || document.body() == null) {
        return "";
      }
      final String body = document.body();
      final int footer = body.indexOf(DISCLAIMER);
      final String content = (footer > 0 ? body.substring(0, footer) : body).strip();
      return content.length() > PREVIEW_CHARS
          ? content.substring(0, PREVIEW_CHARS) + "…"
          : content;
    }
  }
}
