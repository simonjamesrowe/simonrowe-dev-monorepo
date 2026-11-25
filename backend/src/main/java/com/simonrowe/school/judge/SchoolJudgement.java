package com.simonrowe.school.judge;

import com.simonrowe.school.model.SchoolSourceType;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What the publication judge decided about one document, and why.
 *
 * <p>Its own collection rather than fields on {@code SchoolDocument}, deliberately. That record is
 * positional with sixteen components and a dozen copy sites, and a new field there has to be
 * threaded through every one of them or an edit or an approval silently drops it. Keyed by the
 * document's id, so there is one judgement per document and re-judging replaces it.
 *
 * <p>Title and date are copied in so the admin summary still reads sensibly if the document is
 * deleted. In the backup, because nothing re-derives a model's past answer.
 *
 * @param id the judged document's id
 * @param sourceType EMAIL, or PDF for an attachment
 * @param title the document's title when judged
 * @param publishedAt when the school sent it
 * @param contentHash the document text judged; a changed text is judged again
 * @param verdict PUBLIC, PRIVATE or UNSURE
 * @param confidence 0 to 1; 1 for a rule, which is not a matter of degree
 * @param reason one sentence, shown on the admin page
 * @param rule the rule that fired, or null when the model decided
 * @param followsDocumentId for an attachment, the email whose outcome it followed
 * @param senderAuthenticated whether Google verified the sender; publishing requires it
 * @param outcome what was done about it
 * @param model the model asked, or null when a rule decided
 * @param version {@link PublicationJudge#VERSION} at the time
 * @param judgedAt when
 */
@Document(collection = SchoolJudgement.COLLECTION)
public record SchoolJudgement(
    @Id String id,
    SchoolSourceType sourceType,
    String title,
    Instant publishedAt,
    String contentHash,
    PublicationJudge.Verdict verdict,
    double confidence,
    String reason,
    String rule,
    String followsDocumentId,
    boolean senderAuthenticated,
    Outcome outcome,
    String model,
    String version,
    Instant judgedAt
) {

  /** The collection name, referenced by the backup and restore lists. */
  public static final String COLLECTION = "school_judgements";

  /** What the judge did with its verdict. */
  public enum Outcome {
    /** Published to the public tier. */
    APPROVED,
    /** Kept private. */
    DECLINED,
    /** Left in the approvals queue for a person. */
    QUEUED,
    /** Recorded in shadow mode; nothing was decided. */
    SHADOW
  }
}
