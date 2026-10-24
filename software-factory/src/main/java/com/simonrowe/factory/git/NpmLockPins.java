package com.simonrowe.factory.git;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides whether a change to {@code frontend/package-lock.json} does nothing but move existing
 * packages to newer releases from the npm registry.
 *
 * <p>The npm counterpart of {@link DependencyPins}, and what lets an npm vulnerability repair
 * merge itself. It compares the two lockfiles as JSON, never as a diff: a regenerated lockfile adds
 * {@code resolved} and {@code integrity} lines and rewrites a package's own dependency ranges, so
 * a line rule would refuse a perfectly ordinary update.
 *
 * <p>The rule, every part of which must hold:
 *
 * <ul>
 *   <li>Everything outside {@code packages}, and the root project entry ({@code ""}), is
 *       unchanged. So is the set of package entries: nothing added, nothing removed.
 *   <li>Each changed entry moves its {@code version} up, within the same major, to a release (not
 *       a pre-release), and changes nothing but {@link #MAY_CHANGE}. A new {@code bin}, an install
 *       script, or a change of dev/optional flags is refused.
 *   <li>Its {@code resolved} is exactly the registry tarball for that name and version, and its
 *       {@code integrity} is a SHA-512. {@code npm ci} then checks the tarball against it, so a
 *       made-up hash fails CI rather than installing anything.
 *   <li>At most {@link #MAX_CHANGES} entries move. {@code npm update postcss} also moves postcss's
 *       own dependencies within their ranges (on 2026-10-24 it moved nanoid and source-map-js
 *       too), so a rule naming only the requested package would refuse the ordinary case; a cap
 *       instead keeps a large re-resolution for a person.
 * </ul>
 *
 * <p>Whether the requested package actually reached its fixed release is a separate question,
 * {@link #reaches}, which the ticket agent asks before it pushes.
 *
 * <p>Accepted limit, as for {@link DependencyPins}: a new release of a package already in the
 * tree runs in CI and ships, and nothing here can tell a good release from a compromised one.
 */
public final class NpmLockPins {

  /** The only npm lockfile in this repository. */
  public static final String LOCKFILE = "frontend/package-lock.json";

  /** More moved entries than this is a re-resolution for a person, not a repair. */
  public static final int MAX_CHANGES = 25;

  /** Larger than this is not read: today's lockfile is about 0.6 MB. */
  public static final int MAX_CHARS = 8 * 1024 * 1024;

  /** What a regenerated entry may change; any other field must be identical. */
  static final Set<String> MAY_CHANGE =
      Set.of(
          "version",
          "resolved",
          "integrity",
          "dependencies",
          "optionalDependencies",
          "peerDependencies",
          "peerDependenciesMeta",
          "engines",
          "funding",
          "license");

  private static final String NODE_MODULES = "node_modules/";
  private static final String REGISTRY = "https://registry.npmjs.org/";

  /** An npm package name, optionally scoped. Lower case, as the registry requires. */
  public static final Pattern NAME =
      Pattern.compile("(?:@[a-z0-9~-][a-z0-9._~-]{0,100}+/)?[a-z0-9~-][a-z0-9._~-]{0,100}+");

  /** A semantic version; group 4 is the pre-release, if any. */
  private static final Pattern SEMVER =
      Pattern.compile(
          "(%1$s)\\.(%1$s)\\.(%1$s)(?:-(%2$s))?(?:\\+%2$s)?"
              .formatted("[0-9]{1,9}+", "[0-9A-Za-z.-]{1,64}+"));

  private static final Pattern SHA512 = Pattern.compile("sha512-[A-Za-z0-9+/]{86}+==");

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private NpmLockPins() {
  }

  /**
   * One package moved.
   *
   * @param name the package name
   * @param path its entry, e.g. {@code node_modules/vite/node_modules/postcss}
   * @param from the old version
   * @param to the new version
   */
  public record Change(String name, String path, String from, String to) {
  }

  /**
   * The answer.
   *
   * @param versionsOnly true when every change moves a package to a newer registry release
   * @param reason why not; empty when {@code versionsOnly}
   * @param changes what moved, in lockfile order; empty when refused
   */
  public record Verdict(boolean versionsOnly, String reason, List<Change> changes) {

    public Verdict {
      reason = reason == null ? "" : reason;
      changes = changes == null ? List.of() : List.copyOf(changes);
    }

    static Verdict no(final String reason) {
      return new Verdict(false, reason, List.of());
    }
  }

  /**
   * Whether a path is the npm lockfile.
   *
   * @param path a repository-relative path
   * @return true for {@link #LOCKFILE}
   */
  public static boolean isLockfile(final String path) {
    return LOCKFILE.equals(path);
  }

  /**
   * Checks a lockfile change.
   *
   * @param base the lockfile before, or null when unreadable
   * @param head the lockfile after, or null when unreadable
   * @return the verdict
   */
  public static Verdict check(final String base, final String head) {
    if (base == null || head == null) {
      return Verdict.no("`" + LOCKFILE + "` could not be read");
    }
    if (base.length() > MAX_CHARS || head.length() > MAX_CHARS) {
      return Verdict.no("`" + LOCKFILE + "` is too large to check");
    }
    JsonNode before;
    JsonNode after;
    try {
      before = JSON.readTree(base);
      after = JSON.readTree(head);
    } catch (JacksonException exception) {
      return Verdict.no("`" + LOCKFILE + "` is not valid JSON");
    }
    if (!before.isObject() || !after.isObject()
        || !before.path("packages").isObject() || !after.path("packages").isObject()) {
      return Verdict.no("`" + LOCKFILE + "` has no `packages` map");
    }
    for (String field : union(before.propertyNames(), after.propertyNames())) {
      if (!"packages".equals(field) && !Objects.equals(before.get(field), after.get(field))) {
        return Verdict.no("the lockfile's `" + field + "` changed");
      }
    }
    JsonNode oldPackages = before.path("packages");
    JsonNode newPackages = after.path("packages");
    Set<String> oldKeys = new TreeSet<>(oldPackages.propertyNames());
    Set<String> newKeys = new TreeSet<>(newPackages.propertyNames());
    if (!oldKeys.equals(newKeys)) {
      Set<String> added = new TreeSet<>(newKeys);
      added.removeAll(oldKeys);
      Set<String> removed = new TreeSet<>(oldKeys);
      removed.removeAll(newKeys);
      return Verdict.no(
          added.isEmpty()
              ? "it removes `%s`".formatted(removed.iterator().next())
              : "it adds `%s`".formatted(added.iterator().next()));
    }
    List<Change> changes = new ArrayList<>();
    for (Map.Entry<String, JsonNode> entry : newPackages.properties()) {
      String key = entry.getKey();
      JsonNode was = oldPackages.get(key);
      JsonNode now = entry.getValue();
      if (Objects.equals(was, now)) {
        continue;
      }
      String problem = entryProblem(key, was, now);
      if (problem != null) {
        return Verdict.no(problem);
      }
      changes.add(
          new Change(
              nameOf(key), key, was.path("version").asText(""), now.path("version").asText("")));
    }
    if (changes.isEmpty()) {
      return Verdict.no("no package version changed");
    }
    if (changes.size() > MAX_CHANGES) {
      return Verdict.no(
          "it moves %d packages; more than %d is a re-resolution for a person"
              .formatted(changes.size(), MAX_CHANGES));
    }
    return new Verdict(true, "", changes);
  }

  /** Why one changed entry is refused, or null when it is a plain upgrade. */
  private static String entryProblem(final String key, final JsonNode was, final JsonNode now) {
    String name = nameOf(key);
    if (name == null) {
      return "`%s` is not an installed package".formatted(key.isEmpty() ? "(root)" : key);
    }
    if (!was.isObject() || !now.isObject()) {
      return "`%s` is not an object".formatted(key);
    }
    for (String field : union(was.propertyNames(), now.propertyNames())) {
      if (!MAY_CHANGE.contains(field) && !Objects.equals(was.get(field), now.get(field))) {
        return "it changes `%s` of `%s`".formatted(field, key);
      }
    }
    String from = was.path("version").asText("");
    String to = now.path("version").asText("");
    Matcher old = SEMVER.matcher(from);
    Matcher next = SEMVER.matcher(to);
    if (!old.matches() || !next.matches()) {
      return "`%s` moves between versions that are not plain releases (`%s` to `%s`)"
          .formatted(name, from, to);
    }
    if (next.group(4) != null) {
      return "`%s` moves to a pre-release, `%s`".formatted(name, to);
    }
    if (!old.group(1).equals(next.group(1))) {
      return "`%s` changes major version, `%s` to `%s`".formatted(name, from, to);
    }
    if (!raises(old, next)) {
      return "`%s` does not move up (`%s` to `%s`)".formatted(name, from, to);
    }
    String bare = name.substring(name.indexOf('/') + 1);
    String expected = REGISTRY + name + "/-/" + bare + "-" + to + ".tgz";
    if (!expected.equals(now.path("resolved").asText(""))) {
      return "`%s` does not resolve to the npm registry's tarball for %s".formatted(name, to);
    }
    if (!SHA512.matcher(now.path("integrity").asText("")).matches()) {
      return "`%s` has no SHA-512 integrity".formatted(name);
    }
    return null;
  }

  /**
   * Whether every installed copy of a package is at or above a release.
   *
   * @param lockfile the lockfile
   * @param name the package
   * @param fixed a release, e.g. {@code 8.5.10}
   * @return the installed versions below {@code fixed}; empty when every copy reaches it. A
   *     package with no copy answers {@code ["(not installed)"]}, an unreadable lockfile
   *     {@code ["(unreadable)"]}, so neither reads as success
   */
  public static List<String> below(final String lockfile, final String name, final String fixed) {
    Matcher target = SEMVER.matcher(fixed == null ? "" : fixed);
    if (lockfile == null || lockfile.length() > MAX_CHARS || !target.matches()) {
      return List.of("(unreadable)");
    }
    JsonNode packages;
    try {
      packages = JSON.readTree(lockfile).path("packages");
    } catch (JacksonException exception) {
      return List.of("(unreadable)");
    }
    List<String> low = new ArrayList<>();
    boolean found = false;
    for (Map.Entry<String, JsonNode> entry : packages.properties()) {
      if (!name.equals(nameOf(entry.getKey()))) {
        continue;
      }
      found = true;
      String version = entry.getValue().path("version").asText("");
      Matcher installed = SEMVER.matcher(version);
      if (!installed.matches() || (!raises(target, installed) && !sameRelease(target, installed))) {
        low.add(version);
      }
    }
    return found ? List.copyOf(low) : List.of("(not installed)");
  }

  private static boolean sameRelease(final Matcher first, final Matcher second) {
    return first.group(1).equals(second.group(1)) && first.group(2).equals(second.group(2))
        && first.group(3).equals(second.group(3)) && second.group(4) == null;
  }

  /** Whether the new release is higher; a pre-release counts below its release. */
  private static boolean raises(final Matcher old, final Matcher next) {
    for (int group = 1; group <= 3; group++) {
      long from = Long.parseLong(old.group(group));
      long to = Long.parseLong(next.group(group));
      if (to != from) {
        return to > from;
      }
    }
    return old.group(4) != null;
  }

  /** The package an entry installs, or null when the key is not a node_modules path. */
  static String nameOf(final String key) {
    int at = key.lastIndexOf(NODE_MODULES);
    if (at < 0 || (at > 0 && key.charAt(at - 1) != '/')) {
      return null;
    }
    String name = key.substring(at + NODE_MODULES.length());
    return NAME.matcher(name).matches() ? name : null;
  }

  private static Set<String> union(
      final Collection<String> first, final Collection<String> second) {
    Set<String> all = new LinkedHashSet<>(first);
    all.addAll(second);
    return all;
  }

  /**
   * A lockfile read as raw bytes, as text.
   *
   * @param bytes the file
   * @return the text, or null when there is none or it is larger than {@link #MAX_CHARS} bytes
   */
  public static String text(final byte[] bytes) {
    return bytes == null || bytes.length > MAX_CHARS
        ? null
        : new String(bytes, StandardCharsets.UTF_8);
  }
}
