package com.simonrowe.factory.feedback.persistence;

import com.simonrowe.factory.feedback.domain.DistillationStatus;
import com.simonrowe.factory.feedback.domain.Lesson;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Persisted record of one PR's harvested lessons and any resulting distillation. */
@Document(collection = "review_learnings")
public record LearningRecord(
    @Id String id,
    String owner,
    String repository,
    int pullNumber,
    String prTitle,
    String prUrl,
    boolean merged,
    String workflowId,
    Instant harvestedAt,
    String promptVersion,
    List<Lesson> lessons,
    Distillation distillation) {

  public LearningRecord {
    lessons = lessons == null ? List.of() : List.copyOf(lessons);
  }

  /** Deterministic id for upserts: one learning record per PR. */
  public static String idFor(final String owner, final String repository, final int pullNumber) {
    return owner + "/" + repository + "#" + pullNumber;
  }

  /** Outcome of distillation, embedded in the learning record it was derived from. */
  public record Distillation(DistillationStatus status, List<String> prUrls, String detail) {

    public Distillation {
      prUrls = prUrls == null ? List.of() : List.copyOf(prUrls);
    }
  }
}
