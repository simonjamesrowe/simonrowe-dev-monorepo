package com.simonrowe.factory.cvefix.config;

import java.util.List;

/**
 * The only files a CVE-fix run may change, in one place.
 *
 * <p>Read by the agent's tool grant (Task 6) and by the activity's changed-path validation (Task
 * 9), so the two cannot drift. The {@code file} enum in {@code cve-fix-schema.json} is the one
 * intentional duplicate — JSON Schema cannot reference a Java constant — and must be kept in sync
 * by hand.
 */
public final class CveFixAllowedFiles {

  /** Repository-relative paths, exactly as {@code git status --porcelain} reports them. */
  public static final List<String> ALL =
      List.of(
          "gradle/libs.versions.toml",
          "backend/build.gradle.kts",
          "frontend/package.json",
          "frontend/package-lock.json");

  private CveFixAllowedFiles() {
  }
}
