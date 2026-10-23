package com.simonrowe.factory.staticanalysis.domain;

/**
 * One open Sonar issue on the analysed branch's new code.
 *
 * @param key Sonar's issue key, used only to link to the issue
 * @param rule the rule key, such as {@code typescript:S6845}
 * @param severity Sonar's severity, such as {@code MAJOR}
 * @param type {@code BUG}, {@code VULNERABILITY} or {@code CODE_SMELL}
 * @param path the file's path from the repository root, without the project-key prefix
 * @param line the line the issue starts on, or 0 for a file-level issue
 * @param message Sonar's message for this occurrence
 */
public record SonarIssue(
    String key,
    String rule,
    String severity,
    String type,
    String path,
    int line,
    String message) {

  /**
   * Whether this issue is a defect rather than a code smell. Defects are filed at any severity:
   * a minor bug is still a bug, whereas a minor code smell is the noise the floor exists for.
   *
   * <p>Not named {@code isDefect}: Temporal serialises this record with Jackson, which reads an
   * {@code is} method as a property and would put a {@code defect} field on the wire.
   *
   * @return true for {@code BUG} and {@code VULNERABILITY}
   */
  public boolean countsAsDefect() {
    return "BUG".equals(type) || "VULNERABILITY".equals(type);
  }
}
