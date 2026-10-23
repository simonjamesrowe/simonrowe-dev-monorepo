package com.simonrowe.factory.cvefix.workflow;

import com.simonrowe.factory.cvefix.domain.RepairPlan;
import java.util.List;

/**
 * The title, body and identity of one repair ticket, which is also the ticket agent's brief.
 *
 * <p>The body is written for the agent as much as for a person: it says what to change, where
 * this repository keeps versions, and what not to touch. The last part is not only advice. A
 * change that does more than move versions is labelled {@code no-auto-merge} by the agent and
 * refused by the reviewer, so it waits for a person whatever the ticket said.
 */
final class CveRepairTicket {

  private static final String REPOSITORY = "simonjamesrowe/simonrowe-dev-monorepo";
  private static final int MAX_TITLE_IDS = 2;

  private CveRepairTicket() {
  }

  /**
   * Identifies the problem: this component at this version. A bump that took effect changes the
   * version, so a later finding on the new version is a new ticket rather than a regression.
   */
  static List<String> keyParts(final RepairPlan plan) {
    return List.of(REPOSITORY, "repair", plan.project(), plan.purl());
  }

  static String title(final RepairPlan plan) {
    List<String> ids = plan.advisoryIds();
    String advisories = ids.size() <= MAX_TITLE_IDS
        ? String.join(", ", ids)
        : ids.size() + " advisories";
    return "Bump %s to %s (%s)".formatted(plan.coordinate(), plan.targetVersion(), advisories);
  }

  static String body(final RepairPlan plan) {
    return """
        The vulnerability scan found %d advisor%s against `%s` %s in `%s`. OSV lists \
        **%s** as the first release that fixes all of them.

        **Change:** move `%s` from `%s` to `%s`.

        Where this repository keeps versions:
        - `gradle/libs.versions.toml`, `[versions]`: libraries declared in the catalogue.
        - Root `build.gradle.kts`, `ext["<name>.version"]`: libraries whose version the Spring \
        Boot BOM manages (Tomcat, Jackson, Netty and so on). Change the override, or add one.
        - `backend/build.gradle.kts`, `constraints {}`: other transitive libraries. Add or change \
        `implementation("%s:%s")` there.

        Keep the change to version strings, plus a `//` comment naming the advisories. Don't add \
        libraries, plugins, repositories or build logic, and don't edit any other file. Anything \
        more than a version change waits for a person to merge it. If you can't tell where the \
        version comes from, change nothing and say why.

        Advisories: %s

        Filed by the nightly vulnerability scan. Once CI and the code review pass, this merges \
        and deploys without a person.
        """
        .formatted(
            plan.advisoryIds().size(),
            plan.advisoryIds().size() == 1 ? "y" : "ies",
            plan.coordinate(),
            plan.currentVersion(),
            plan.project(),
            plan.targetVersion(),
            plan.coordinate(),
            plan.currentVersion(),
            plan.targetVersion(),
            plan.coordinate(),
            plan.targetVersion(),
            String.join(", ", plan.advisoryIds()));
  }
}
