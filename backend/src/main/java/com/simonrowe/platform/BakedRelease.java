package com.simonrowe.platform;

import java.time.Instant;
import java.util.List;

/**
 * One commit on {@code main}, as baked into the image at build time.
 *
 * <p>Because {@code main} is squash-merged and Publish runs on every merge, one commit is one
 * release. There is deliberately no "commits within a release" concept.
 *
 * @param sha the full commit SHA
 * @param commitTime when the commit was authored
 * @param subject the subject line
 * @param body the message body, empty when there is none
 * @param filesChanged the paths the commit touched
 */
public record BakedRelease(
    String sha, Instant commitTime, String subject, String body, List<String> filesChanged) {

  private static final int SHORT_SHA_LENGTH = 7;
  private static final String OTHER_TYPE = "other";

  public BakedRelease {
    filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
  }

  /**
   * The short SHA as rendered on the page and in GitHub links.
   *
   * @return the first seven characters of the SHA
   */
  public String shortSha() {
    return sha.substring(0, Math.min(SHORT_SHA_LENGTH, sha.length()));
  }

  /**
   * The conventional-commit type, used for the badge on each changelog entry.
   *
   * @return {@code feat}, {@code fix}, {@code chore}, {@code docs}, {@code perf} and so on,
   *     or {@code other} when the subject does not follow the convention
   */
  public String type() {
    int colon = subject.indexOf(':');
    if (colon <= 0) {
      return OTHER_TYPE;
    }
    String prefix = subject.substring(0, colon);
    // Strip an optional scope: "fix(api)" -> "fix".
    int scope = prefix.indexOf('(');
    String type = (scope > 0 ? prefix.substring(0, scope) : prefix).trim();
    // A space means this was prose that happened to contain a colon, not a type.
    return type.isEmpty() || type.contains(" ") ? OTHER_TYPE : type.toLowerCase(
        java.util.Locale.ROOT);
  }
}
