package com.simonrowe.factory.cvefix.domain;

import com.simonrowe.factory.cvefix.osv.OsvClient;
import com.simonrowe.factory.cvefix.osv.OsvClient.Advisory;
import com.simonrowe.factory.git.NpmLockPins;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether one vulnerable component is a bump the factory may make without a person.
 *
 * <p>Every answer it cannot be sure of is "no", which leaves the component in the consolidated
 * report for a person, exactly as before: a PURL that is neither Maven nor npm, an advisory OSV
 * does not know, an advisory with no fix, or a fix in a different major version (an API break is a
 * person's call, not a version bump). An npm fix must also be a plain release, because the
 * lockfile check refuses pre-releases.
 *
 * <p>The plan becomes the factory's brief with no person reading it first, so it carries no
 * third-party prose at all: every value in it must match a pattern with no room for an
 * instruction (advisory ids, a Maven coordinate or npm name, two versions). An advisory's
 * recommendation text never reaches the ticket.
 */
public final class RepairPlanner {

  /** {@code pkg:maven/<group>/<artifact>@<version>}, qualifiers ignored. */
  private static final Pattern MAVEN_PURL =
      Pattern.compile("pkg:maven/([A-Za-z0-9._-]++)/([A-Za-z0-9._-]++)@([A-Za-z0-9._+-]++).*+");

  /** {@code pkg:npm/<name>@<version>}, a scope's {@code @} written as {@code %40}. */
  private static final Pattern NPM_PURL =
      Pattern.compile("pkg:npm/((?:%40[a-z0-9._~-]++/)?[a-z0-9._~-]++)@([A-Za-z0-9._+-]++).*+");

  private static final Pattern ADVISORY_ID = Pattern.compile("[A-Za-z0-9-]{1,40}+");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._+-]{1,64}+");

  private RepairPlanner() {
  }

  /**
   * The component's Maven coordinate, or empty when it is not a Maven PURL.
   *
   * @param purl the PURL
   * @return {@code group:artifact}
   */
  public static Optional<String> coordinate(final String purl) {
    Matcher matcher = purl == null || purl.length() > 300 ? null : MAVEN_PURL.matcher(purl);
    return matcher != null && matcher.matches()
        ? Optional.of(matcher.group(1) + ":" + matcher.group(2))
        : Optional.empty();
  }

  /**
   * The component's npm package name, or empty when it is not an npm PURL.
   *
   * @param purl the PURL
   * @return the name, scope included, e.g. {@code @babel/core}
   */
  public static Optional<String> npmName(final String purl) {
    Matcher matcher = purl == null || purl.length() > 300 ? null : NPM_PURL.matcher(purl);
    if (matcher == null || !matcher.matches()) {
      return Optional.empty();
    }
    String name = matcher.group(1).replace("%40", "@");
    return NpmLockPins.NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
  }

  /**
   * The ecosystem and package OSV should be asked about.
   *
   * @param purl the PURL
   * @return {ecosystem, name}, or empty for a PURL no repair handles
   */
  public static Optional<List<String>> osvPackage(final String purl) {
    Optional<String> maven = coordinate(purl);
    if (maven.isPresent()) {
      return Optional.of(List.of(OsvClient.MAVEN, maven.get()));
    }
    return npmName(purl).map(name -> List.of(OsvClient.NPM, name));
  }

  /**
   * The plan for a component, given what OSV says about its version.
   *
   * @param component the component's findings
   * @param advisories OSV's advisories against the same version
   * @return the plan, or empty when a person has to decide
   */
  public static Optional<RepairPlan> plan(
      final ComponentFindings component, final List<Advisory> advisories) {
    Optional<List<String>> pkg = osvPackage(component.purl());
    if (pkg.isEmpty() || advisories.isEmpty()) {
      return Optional.empty();
    }
    String ecosystem = pkg.get().get(0);
    MavenVersion current = MavenVersion.of(component.componentVersion());
    MavenVersion target = null;
    for (Advisory advisory : advisories) {
      if (advisory.fixedVersion() == null) {
        return Optional.empty();
      }
      MavenVersion fixed = MavenVersion.of(advisory.fixedVersion());
      if (target == null || fixed.compareTo(target) > 0) {
        target = fixed;
      }
    }
    if (target.compareTo(current) <= 0 || target.major() != current.major()
        || current.major() < 0
        || !VERSION.matcher(component.componentVersion()).matches()
        || !VERSION.matcher(target.toString()).matches()) {
      return Optional.empty();
    }
    if (OsvClient.NPM.equals(ecosystem)
        && !NpmRepairTitle.RELEASE.matcher(target.toString()).matches()) {
      return Optional.empty();
    }
    List<String> ids = component.vulnerabilityIds().stream()
        .filter(id -> id != null && ADVISORY_ID.matcher(id).matches())
        .toList();
    if (ids.isEmpty()) {
      return Optional.empty();
    }
    String severity = component.findings().isEmpty()
        ? "UNASSIGNED"
        : component.findings().get(0).severity();
    return Optional.of(
        new RepairPlan(
            component.project(),
            component.purl(),
            pkg.get().get(1),
            component.componentVersion(),
            target.toString(),
            ids,
            severity,
            ecosystem));
  }
}
