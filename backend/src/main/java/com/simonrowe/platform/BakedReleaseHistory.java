package com.simonrowe.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The last 50 commits on {@code main}, baked into the image by the {@code generateReleaseHistory}
 * Gradle task and parsed once at construction.
 *
 * <p>The format is git's own output with ASCII record ({@code 0x1e}) and unit ({@code 0x1f})
 * separators rather than JSON, because generating JSON in Gradle means hand-rolling string
 * escaping for arbitrary commit messages — a bug waiting to happen. Separators are used instead
 * of newlines because commit bodies contain newlines.
 *
 * <p>An absent resource yields an empty list. That is the normal state for a build made outside
 * a git checkout, and the page renders "no release history yet".
 */
@Component
public class BakedReleaseHistory {

  private static final Logger LOG = LoggerFactory.getLogger(BakedReleaseHistory.class);

  private static final String RESOURCE = "platform/release-history.txt";
  private static final String RECORD_SEPARATOR = "\u001e";
  private static final String UNIT_SEPARATOR = "\u001f";
  private static final int FIELD_COUNT = 5;

  private final List<BakedRelease> releases;

  public BakedReleaseHistory() {
    this.releases = parse(read());
  }

  /**
   * The baked commits, newest first.
   *
   * @return the releases; empty when the resource is absent, never null
   */
  public List<BakedRelease> releases() {
    return releases;
  }

  private static String read() {
    ClassPathResource resource = new ClassPathResource(RESOURCE);
    if (!resource.exists()) {
      LOG.info("No {} on the classpath — release history will be empty", RESOURCE);
      return "";
    }
    try (InputStream stream = resource.getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOG.warn("Could not read {}: {}", RESOURCE, e.getMessage());
      return "";
    }
  }

  /**
   * Parses the baked git output.
   *
   * <p>Package-private and static so it can be tested without a classpath resource.
   *
   * @param raw the resource contents
   * @return the parsed releases, newest first; malformed records are skipped, not fatal
   */
  static List<BakedRelease> parse(final String raw) {
    // Known limitation: only the FIELD_COUNT split is validated, not the byte content of each
    // field. A literal 0x1e/0x1f control byte inside a commit subject or body would silently
    // misalign record/field boundaries instead of being flagged. Accepted because those bytes
    // essentially never occur in real commit text, and separators were chosen over line-based
    // parsing specifically because commit bodies contain newlines.
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<BakedRelease> parsed = new ArrayList<>();
    for (String record : raw.split(RECORD_SEPARATOR)) {
      if (record.isBlank()) {
        continue;
      }
      String[] fields = record.split(UNIT_SEPARATOR, -1);
      if (fields.length < FIELD_COUNT) {
        LOG.warn("Skipping malformed release record with {} fields", fields.length);
        continue;
      }
      try {
        parsed.add(new BakedRelease(
            fields[0].trim(),
            Instant.ofEpochSecond(Long.parseLong(fields[1].trim())),
            fields[2].trim(),
            fields[3].trim(),
            files(fields[4])));
      } catch (NumberFormatException e) {
        LOG.warn("Skipping release record with unparseable timestamp '{}'", fields[1]);
      }
    }
    return List.copyOf(parsed);
  }

  private static List<String> files(final String block) {
    return Arrays.stream(block.split("\n"))
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();
  }
}
