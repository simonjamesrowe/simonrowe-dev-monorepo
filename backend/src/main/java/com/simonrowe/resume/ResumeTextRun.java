package com.simonrowe.resume;

/**
 * A stretch of text sharing one style, as parsed out of CMS markdown.
 *
 * @param text the literal text
 * @param bold whether the run came from strong emphasis
 * @param italic whether the run came from emphasis
 */
public record ResumeTextRun(String text, boolean bold, boolean italic) {

  public static ResumeTextRun plain(String text) {
    return new ResumeTextRun(text, false, false);
  }
}
