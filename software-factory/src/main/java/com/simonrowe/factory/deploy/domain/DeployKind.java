package com.simonrowe.factory.deploy.domain;

/**
 * What a deploy changes, which decides how it runs.
 *
 * <p>Ordered from least to most disruptive.
 */
public enum DeployKind {
  /**
   * Every image and every service's configuration already match the commit. Nothing is pulled,
   * recreated or put behind the maintenance page: a documentation-only merge.
   */
  NOTHING,

  /**
   * Only services no visitor reaches changed ({@link DeployPlan#FACTORY_SERVICES}). They are
   * recreated with the site left up: no maintenance page and no nginx restart, on the way in or on
   * a rollback.
   */
  FACTORY,

  /**
   * Anything a visitor reaches changed, or the plan could not tell. The full flow, maintenance
   * page included, exactly as every deploy ran before the plan existed.
   */
  SITE
}
