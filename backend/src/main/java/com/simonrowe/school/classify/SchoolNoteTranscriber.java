package com.simonrowe.school.classify;

import com.embabel.agent.api.common.AgentImage;
import com.embabel.agent.api.common.Ai;
import com.simonrowe.school.SchoolProperties;
import com.simonrowe.school.usage.SchoolUsage;
import com.simonrowe.school.usage.SchoolUsageRecorder;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Reads a photographed school page into editable text without storing the image. */
@Component
public class SchoolNoteTranscriber {

  private static final Logger LOG = LoggerFactory.getLogger(SchoolNoteTranscriber.class);
  private static final long ESTIMATED_IMAGE_INPUT_TOKENS = 2500;
  private static final int CHARS_PER_TOKEN = 4;

  private static final String PROMPT = """
      Transcribe this photograph of a page for a school assistant's records.

      The photograph was taken on a phone. It may be rotated ninety or a hundred and eighty
      degrees, taken at an angle, partly in shadow or washed out by glare, and the page may be
      lying on top of something else. Read it whatever way up it is.

      Rules:
      - Transcribe what is on the page, in the order it appears, and nothing else. Keep the
        headings, the lists and the line breaks.
      - Transcribe ONLY the page. Ignore the desk, the book it is resting on, a hand holding
        it, and any page behind or underneath it.
      - Never fill in a word you cannot read. Write [unclear] in its place. A school assistant
        quoting an invented word to a parent is worse than one admitting it could not read it.
      - Do not summarise, tidy up, correct spelling or explain anything. This is a
        transcription.
      - Copy any web address or date exactly as written.
      - If the image contains no readable text at all, return an empty text field.

      Also give a short title for this page, as a filing label - what it is and who it is for,
      e.g. "Year 6 spellings - Autumn week 2" or "Nativity letter - Reception". Take the year
      group and the week from the page itself where it says so, and leave them out where it
      does not. Never guess a year group.
      """;

  private final Ai ai;
  private final SchoolProperties properties;
  private final SchoolUsageRecorder usageRecorder;

  public SchoolNoteTranscriber(final Ai ai, final SchoolProperties properties,
      final SchoolUsageRecorder usageRecorder) {
    this.ai = ai;
    this.properties = properties;
    this.usageRecorder = usageRecorder;
  }

  /**
   * Transcribes one image, returning empty when the model fails or finds no readable text.
   *
   * @param image the image bytes
   * @param mimeType the image MIME type
   * @return editable text and a suggested title, or empty
   */
  public Optional<Transcription> transcribe(final byte[] image, final String mimeType) {
    final TranscribedNote note;
    try {
      note = ai.withLlm(properties.visionModel())
          .withImage(AgentImage.create(mimeType, image))
          .createObjectIfPossible(PROMPT, TranscribedNote.class);
    } catch (Exception e) {
      LOG.warn("School note transcription failed: {}", e.getMessage());
      return Optional.empty();
    }

    final String text = normalise(note == null ? null : note.text());
    final String title = normalise(note == null ? null : note.title());
    usageRecorder.recordEstimatedTokens(
        SchoolUsage.Kind.TRANSCRIBE,
        properties.visionModel(),
        ESTIMATED_IMAGE_INPUT_TOKENS + PROMPT.length() / CHARS_PER_TOKEN,
        (text.length() + title.length()) / CHARS_PER_TOKEN);
    if (text.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new Transcription(text, title));
  }

  private static String normalise(final String value) {
    return value == null ? "" : value.trim();
  }

  /** Text and suggested title returned to the review-before-save screen. */
  public record Transcription(String text, String title) {
  }
}
