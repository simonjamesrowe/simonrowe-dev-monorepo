package com.simonrowe.factory.deploy.domain;

/**
 * Which host a deploy acts on.
 *
 * <p>Each host has its own deployer, its own deploy queue and its own workflow: {@code
 * deploy-prod} on the Raspberry Pi, {@code deploy-factory} on the factory host. A Publish webhook
 * starts both, and each plans its own host and does nothing if nothing there changed.
 */
public enum DeployHost {
  /** The Raspberry Pi: the public site, behind the maintenance page while it changes. */
  SITE,

  /**
   * The factory host (docker-compose.factory.yml). No visitor reaches anything on it, so it has
   * no maintenance page and no nginx, and every change there is deployed the way a factory-only
   * change was on the Pi: scoped, with the site left alone.
   */
  FACTORY
}
