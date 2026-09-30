package com.simonrowe.portfolio;

/** Where a portfolio project is in its life; only {@link #COMING_SOON} is shown as a silhouette. */
public enum ProjectStatus {
  LIVE,
  BETA,
  IN_DEVELOPMENT,
  COMING_SOON
}
