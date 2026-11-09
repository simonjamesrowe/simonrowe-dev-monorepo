package com.simonrowe.factory.deploy.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What kind of deploy this is, decided before anything changes.
 *
 * <p>Decided by what actually differs on the host, not by which paths the commit touched: an image
 * Publish re-tagged unchanged compares equal to the running one, so a merge that touched only the
 * software factory changes only the software factory's containers, whatever else is in its diff.
 *
 * <p>Pure and deterministic: it runs inside the workflow.
 *
 * @param kind what kind of deploy
 * @param services the services {@code pull}, {@code recreate} and {@code rollback} act on; for
 *     {@link DeployKind#SITE} the request's whole list, as before the plan existed
 * @param detail one line for the run record and the progress query
 */
public record DeployPlan(DeployKind kind, List<String> services, String detail) {

  /**
   * The services no visitor reaches.
   *
   * <p>Recreating them cannot take the public site down, so a deploy that changes only these needs
   * no maintenance page. Both run the software factory's own work: {@code software-factory} is the
   * webhook receiver and Temporal workers, {@code page-reader} the headless browser it drives.
   *
   * <p>{@code deployer} is not here because it is never deployed by itself (it excludes itself
   * from every recreate). Anything not named here counts as part of the site, which is the safe
   * default for a service added later.
   */
  public static final Set<String> FACTORY_SERVICES = Set.of("software-factory", "page-reader");

  public DeployPlan {
    services = services == null ? List.of() : List.copyOf(services);
    detail = detail == null ? "" : detail;
  }

  /**
   * The plan for a deploy that could not be planned, or was started by a build without planning.
   *
   * @param services the request's services
   * @param reason why there is no finer plan
   * @return a {@link DeployKind#SITE} plan over every service
   */
  public static DeployPlan everything(final List<String> services, final String reason) {
    return new DeployPlan(DeployKind.SITE, services, reason);
  }

  /**
   * {@link #everything}, on a given host.
   *
   * <p>On the factory host "everything" is still a {@link DeployKind#FACTORY} deploy: that host has
   * no maintenance page to raise and no nginx to restart, so the only thing a doubt can buy there
   * is deploying every one of its services, which a factory scope over all of them already does.
   *
   * @param services the request's services
   * @param reason why there is no finer plan
   * @param host the host the deploy acts on
   * @return the plan
   */
  public static DeployPlan everything(
      final List<String> services, final String reason, final DeployHost host) {
    if (host == DeployHost.FACTORY && !services.isEmpty()) {
      return new DeployPlan(DeployKind.FACTORY, services, reason);
    }
    return everything(services, reason);
  }

  /**
   * Decides the kind of deploy on a given host.
   *
   * <p>On the site the rules below apply as they always did. On the factory host every service is
   * the factory's, so any change, and any doubt, is a {@link DeployKind#FACTORY} deploy of all of
   * them, and only "nothing changed" is different.
   *
   * @param facts what the script found; null or unreadable means "could not tell"
   * @param services the request's services
   * @param host the host the deploy acts on
   * @return the plan
   */
  public static DeployPlan decide(
      final PlanFacts facts, final List<String> services, final DeployHost host) {
    if (host != DeployHost.FACTORY) {
      return decide(facts, services);
    }
    if (facts == null || !facts.readable()) {
      return everything(
          services,
          "could not tell what changed on the factory host, so deploying all of it: %s"
              .formatted(facts == null ? "no plan" : facts.detail()),
          host);
    }
    Set<String> changed = new LinkedHashSet<>(facts.changedImages());
    changed.addAll(facts.changedConfig());
    changed.addAll(facts.changedHostFiles());
    if (changed.isEmpty()) {
      return new DeployPlan(
          DeployKind.NOTHING,
          List.of(),
          "nothing to deploy on the factory host: every image and service configuration matches");
    }
    return everything(
        services, "factory host changed: " + String.join(", ", changed), host);
  }

  /**
   * Decides the kind of deploy.
   *
   * <p>Every doubt resolves towards {@link DeployKind#SITE}. A wrong {@code SITE} costs a
   * maintenance page nobody needed, which is what every deploy cost before this; a wrong {@code
   * FACTORY} recreates something visitors use with the site exposed, and a wrong {@code NOTHING}
   * reports a commit as deployed while running the previous one.
   *
   * @param facts what the script found; null or unreadable means "could not tell"
   * @param services the request's services
   * @return the plan
   */
  public static DeployPlan decide(final PlanFacts facts, final List<String> services) {
    if (facts == null || !facts.readable()) {
      return everything(
          services,
          "could not tell what changed, so deploying everything: %s"
              .formatted(facts == null ? "no plan" : facts.detail()));
    }

    // Bind-mounted files are in no hash, and every one of them belongs to a site-side service:
    // nginx's routes and maintenance pages, alloy, searxng, clickhouse, temporal.
    if (!facts.changedHostFiles().isEmpty()) {
      return everything(
          services, "host configuration changed: " + String.join(", ", facts.changedHostFiles()));
    }

    Set<String> changed = new LinkedHashSet<>(facts.changedImages());
    changed.addAll(facts.changedConfig());
    if (changed.isEmpty()) {
      return new DeployPlan(
          DeployKind.NOTHING,
          List.of(),
          "nothing to deploy: every image and service configuration already matches");
    }

    if (FACTORY_SERVICES.containsAll(changed)) {
      List<String> scoped = new ArrayList<>();
      for (String service : services) {
        if (FACTORY_SERVICES.contains(service)) {
          scoped.add(service);
        }
      }
      // Never an empty scope: the script reads an empty SERVICES as "use the default", which is
      // every image. A request that names none of the factory's services cannot be narrowed to
      // them, so it is deployed the old way.
      if (!scoped.isEmpty()) {
        return new DeployPlan(
            DeployKind.FACTORY,
            scoped,
            "software factory only (" + String.join(", ", changed) + "); the site is left up");
      }
    }

    return everything(services, "site changed: " + String.join(", ", changed));
  }

  /** Whether this deploy puts the public site behind the maintenance page. */
  public boolean raisesMaintenancePage() {
    return kind == DeployKind.SITE;
  }

  /**
   * How the shell phases are scoped, or null for the unscoped calls every deploy made before the
   * plan existed.
   *
   * <p>Null for {@link DeployKind#SITE} on purpose: that path then schedules exactly the activity
   * calls it always did, so the configured services and nginx restart apply unchanged.
   *
   * @return the scope, or null
   */
  public PhaseScope scope() {
    return kind == DeployKind.FACTORY ? new PhaseScope(services, false) : null;
  }
}
