package com.simonrowe.coparent.school;

import com.simonrowe.coparent.model.Child;
import com.simonrowe.school.model.AcademicYear;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed rules behind "From school" for mail addressed to one family: which children a
 * personal letter names, which emails are "a report is ready" prompts, and the one address a
 * report link may point at.
 *
 * <p>Rules, never a model: a personal letter is offered to the mailbox family only when its
 * subject or file name names one of their children by first name together with the family
 * surname (or the child's own year, "Y4 Mia"), or by full name. Names are compared as whole
 * words after splitting the text on anything that is not a letter, digit, apostrophe or hyphen,
 * so a child's name is never read as a pattern and nothing can backtrack. The two patterns here
 * are possessive and bounded, and every rule is tested against a 100,000-character input.
 */
final class SchoolLetterRules {

  /** The only host a report link may point at. Compared on the parsed host, never a prefix. */
  static final String REPORT_HOST = "app.insighttracking.com";

  /** The reporting system's sender. */
  static final String REPORT_SENDER = "system@insighttracking.com";

  private static final int HTTPS_PORT = 443;
  private static final int MAX_TITLE = 120;

  private static final Pattern WORD_BREAK = Pattern.compile("[^\\p{L}\\p{N}'’-]++");

  /** "A report for Mia is ready", allowing a surname or two before "is ready". */
  private static final Pattern REPORT_SUBJECT = Pattern.compile("""
      ^\\s*+a\\s++report\\s++for\\s++(\\p{L}[\\p{L}'’-]{0,40}+)\
      (?:\\s++(?!is\\b)\\p{L}[\\p{L}'’-]{0,40}+){0,3}+\\s++is\\s++ready\\b""",
      Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** A web address in plain text, bounded so a pathological body cannot run on. */
  private static final Pattern WEB_ADDRESS = Pattern.compile(
      "https?+://[^\\s<>\"'()\\[\\]{}]{1,2000}+", Pattern.CASE_INSENSITIVE);

  private static final Pattern REPLY_PREFIX = Pattern.compile(
      "^(?:\\s*+(?:re|fwd|fw)\\s*+:)++\\s*+", Pattern.CASE_INSENSITIVE);

  private SchoolLetterRules() {
  }

  // ---- personal letters --------------------------------------------------------------------

  /**
   * The children a personal letter names in its subject or attachment file name.
   *
   * @param subject the email's subject
   * @param filename the attachment's file name
   * @param children the mailbox family's children
   * @param on the date the letter was sent, for the child's year group
   * @return the children it names, in the order given
   */
  static List<Child> childrenNamed(final String subject, final String filename,
      final List<Child> children, final LocalDate on) {
    final List<String> subjectWords = words(subject);
    final List<String> fileWords = words(stripExtension(filename));
    final Set<String> all = new LinkedHashSet<>(subjectWords);
    all.addAll(fileWords);
    final Set<String> surnames = new LinkedHashSet<>();
    for (final Child child : children) {
      final List<String> name = words(child.fullName());
      if (name.size() > 1) {
        surnames.add(name.getLast());
      }
    }
    final boolean surnamed = surnames.stream().anyMatch(all::contains);
    final List<Child> named = new ArrayList<>();
    for (final Child child : children) {
      final List<String> name = words(child.fullName());
      if (name.isEmpty()) {
        continue;
      }
      final boolean fullName = name.size() > 1
          && (containsRun(subjectWords, name) || containsRun(fileWords, name));
      final boolean first = all.contains(name.getFirst());
      final boolean ownYear = first && ChildSchoolYear.yearGroupOf(child, on)
          .or(() -> ChildSchoolYear.yearGroupFor(child.dateOfBirth(), on))
          .map(year -> namesYear(subjectWords, year) || namesYear(fileWords, year))
          .orElse(false);
      if (fullName || (first && surnamed) || ownYear) {
        named.add(child);
      }
    }
    return named;
  }

  /** Whether the words name a year group: "Y4", "Yr4", "Year 4", "Yr 4", or "Reception". */
  static boolean namesYear(final List<String> words, final String yearGroup) {
    if ("Reception".equals(yearGroup)) {
      return words.contains("reception");
    }
    final String number = yearGroup.replace("Year ", "").strip();
    for (int index = 0; index < words.size(); index++) {
      final String word = words.get(index);
      if (word.equals("y" + number) || word.equals("yr" + number)
          || word.equals("year" + number)) {
        return true;
      }
      if ((word.equals("year") || word.equals("yr") || word.equals("y"))
          && index + 1 < words.size() && words.get(index + 1).equals(number)) {
        return true;
      }
    }
    return false;
  }

  // ---- report prompts ----------------------------------------------------------------------

  /**
   * The first name in a "A report for Mia is ready" subject.
   *
   * @param subject an email subject
   * @return the first name, or empty when this is not that email
   */
  static Optional<String> reportFirstName(final String subject) {
    if (subject == null) {
      return Optional.empty();
    }
    final Matcher matcher = REPORT_SUBJECT.matcher(subject);
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  /**
   * The child a report email names, by first name.
   *
   * @param firstName the name in the subject
   * @param children the mailbox family's children
   * @return the child, or empty when none or more than one has that first name
   */
  static Optional<Child> reportChild(final String firstName, final List<Child> children) {
    final List<String> wanted = words(firstName);
    if (wanted.size() != 1) {
      return Optional.empty();
    }
    final List<Child> matches = children.stream()
        .filter(child -> {
          final List<String> name = words(child.fullName());
          return !name.isEmpty() && name.getFirst().equals(wanted.getFirst());
        })
        .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  /**
   * The first link to the reporting system, from the email's recorded links and then from any
   * address in its text. Anything that does not parse to {@code https://app.insighttracking.com}
   * is ignored, so a look-alike host such as {@code app.insighttracking.com.evil.example} can
   * never become a button in the app.
   *
   * @param body the email's text
   * @param links the hyperlinks Term Time recorded from its markup
   * @return the link, or empty when there is none to trust
   */
  static Optional<String> reportLink(final String body, final Collection<String> links) {
    final List<String> candidates = new ArrayList<>();
    if (links != null) {
      candidates.addAll(links);
    }
    if (body != null) {
      final Matcher matcher = WEB_ADDRESS.matcher(body);
      while (matcher.find()) {
        candidates.add(trimTrailingPunctuation(matcher.group()));
      }
    }
    return candidates.stream().map(SchoolLetterRules::trustedReportLink)
        .flatMap(Optional::stream).findFirst();
  }

  /**
   * A link, if it is on the reporting system's own origin.
   *
   * @param value an address
   * @return it, normalised, when its scheme is https, its host exactly
   *     {@value #REPORT_HOST}, with no user info and the default port; empty otherwise
   */
  static Optional<String> trustedReportLink(final String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    final URI uri;
    try {
      uri = new URI(value.strip());
    } catch (URISyntaxException malformed) {
      return Optional.empty();
    }
    final boolean trusted = "https".equalsIgnoreCase(uri.getScheme())
        && uri.getHost() != null && REPORT_HOST.equalsIgnoreCase(uri.getHost())
        && uri.getRawUserInfo() == null
        && (uri.getPort() == -1 || uri.getPort() == HTTPS_PORT);
    return trusted ? Optional.of(uri.toString()) : Optional.empty();
  }

  /**
   * A title for a report from the month it was published: July is the end-of-year report,
   * September to December the autumn one, January to April the spring one.
   *
   * @param sentOn the date the school published it
   * @return such as {@code Autumn report 2026/27}
   */
  static String reportTitle(final LocalDate sentOn) {
    final Month month = sentOn.getMonth();
    final String kind;
    if (month == Month.JULY) {
      kind = "End-of-year report";
    } else if (month.getValue() >= Month.SEPTEMBER.getValue()) {
      kind = "Autumn report";
    } else if (month.getValue() <= Month.APRIL.getValue()) {
      kind = "Spring report";
    } else {
      kind = "School report";
    }
    return kind + " " + AcademicYear.of(sentOn);
  }

  // ---- letters -----------------------------------------------------------------------------

  /**
   * A letter's title, from the school's own words: the subject without reply prefixes, or the
   * file name when the subject is empty.
   *
   * @param subject the email's subject
   * @param filename the attachment's file name
   * @return a title of at most 120 characters, never blank
   */
  static String letterTitle(final String subject, final String filename) {
    String title = subject == null ? "" : REPLY_PREFIX.matcher(subject.strip()).replaceFirst("");
    if (title.isBlank()) {
      title = stripExtension(filename).replace('_', ' ').strip();
    }
    if (title.isBlank()) {
      title = "School letter";
    }
    title = title.replaceAll("\\s++", " ").strip();
    return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE).strip() : title;
  }

  /** A child's first name as written, for reasons and email text. */
  static String firstName(final Child child) {
    final String name = child.fullName() == null ? "" : child.fullName().strip();
    final int space = name.indexOf(' ');
    return space < 0 ? name : name.substring(0, space);
  }

  // ---- words -------------------------------------------------------------------------------

  /** Lower-cased whole words, with possessive endings and edge punctuation removed. */
  static List<String> words(final String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    return Arrays.stream(WORD_BREAK.split(text))
        .map(SchoolLetterRules::cleanWord)
        .filter(word -> !word.isEmpty())
        .toList();
  }

  private static String cleanWord(final String raw) {
    String word = raw.toLowerCase(Locale.ROOT).replace('’', '\'');
    if (word.endsWith("'s")) {
      word = word.substring(0, word.length() - 2);
    }
    int start = 0;
    int end = word.length();
    while (start < end && (word.charAt(start) == '\'' || word.charAt(start) == '-')) {
      start++;
    }
    while (end > start && (word.charAt(end - 1) == '\'' || word.charAt(end - 1) == '-')) {
      end--;
    }
    return word.substring(start, end);
  }

  private static boolean containsRun(final List<String> words, final List<String> run) {
    return run.size() <= words.size() && java.util.Collections.indexOfSubList(words, run) >= 0;
  }

  private static String stripExtension(final String filename) {
    if (filename == null) {
      return "";
    }
    final int dot = filename.lastIndexOf('.');
    return dot > 0 ? filename.substring(0, dot) : filename;
  }

  private static String trimTrailingPunctuation(final String value) {
    int end = value.length();
    while (end > 0 && ".,;:!?".indexOf(value.charAt(end - 1)) >= 0) {
      end--;
    }
    return value.substring(0, end);
  }
}
