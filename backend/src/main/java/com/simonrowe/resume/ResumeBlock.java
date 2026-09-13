package com.simonrowe.resume;

import java.util.List;

/**
 * One rendered unit of a job description: a run-in sub-heading, a bullet, or a plain
 * paragraph.
 *
 * @param kind how the block is laid out
 * @param runs the styled text making up the block
 */
public record ResumeBlock(Kind kind, List<ResumeTextRun> runs) {

  public enum Kind {
    /** A markdown heading, rendered as a bold label above the bullets it introduces. */
    HEADING,
    /** A list item, rendered with a bullet glyph and a hanging indent. */
    BULLET,
    /** Prose outside any list. */
    PARAGRAPH
  }

  public ResumeBlock {
    runs = List.copyOf(runs);
  }

  /** The block's text with all styling flattened away. Used for measuring and tests. */
  public String plainText() {
    StringBuilder sb = new StringBuilder();
    for (ResumeTextRun run : runs) {
      sb.append(run.text());
    }
    return sb.toString();
  }

  public boolean isBlank() {
    return plainText().isBlank();
  }
}
