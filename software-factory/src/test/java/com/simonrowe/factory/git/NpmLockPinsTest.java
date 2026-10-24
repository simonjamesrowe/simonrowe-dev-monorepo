package com.simonrowe.factory.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.simonrowe.factory.git.NpmLockPins.Verdict;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Shapes taken from the real 2026-10-24 update (#282): nanoid, postcss and source-map-js. */
class NpmLockPinsTest {

  /** Real hashes are 86 base64 characters and a {@code ==}; only the shape is checked. */
  private static final String NANOID_INTEGRITY = "sha512-" + "uKdg2G3G/+".repeat(8) + "abcdef==";
  private static final String POSTCSS_INTEGRITY = "sha512-" + "YUbWYCgE+/".repeat(8) + "ghijkl==";

  private static final String BASE = """
      {
        "name": "simonrowe-frontend",
        "version": "0.0.1",
        "lockfileVersion": 3,
        "requires": true,
        "packages": {
          "": {
            "name": "simonrowe-frontend",
            "dependencies": { "vite": "^6.0.0" }
          },
          "node_modules/nanoid": {
            "version": "3.3.11",
            "funding": [ { "type": "github", "url": "https://github.com/sponsors/ai" } ],
            "license": "MIT",
            "bin": { "nanoid": "bin/nanoid.cjs" }
          },
          "node_modules/postcss": {
            "version": "8.5.6",
            "license": "MIT",
            "dependencies": { "nanoid": "^3.3.11", "picocolors": "^1.1.1" },
            "engines": { "node": "^10 || ^12 || >=14" }
          },
          "node_modules/vite/node_modules/@scope/thing": {
            "version": "1.2.3",
            "resolved": "https://registry.npmjs.org/@scope/thing/-/thing-1.2.3.tgz",
            "integrity": "%s",
            "dev": true
          }
        }
      }
      """.formatted(POSTCSS_INTEGRITY);

  private static final String HEAD = BASE
      .replace("""
          "version": "3.3.11",
          """, """
          "version": "3.3.20",
          "resolved": "https://registry.npmjs.org/nanoid/-/nanoid-3.3.20.tgz",
          "integrity": "%s",
          """.formatted(NANOID_INTEGRITY))
      .replace("""
          "version": "8.5.6",
          """, """
          "version": "8.5.31",
          "resolved": "https://registry.npmjs.org/postcss/-/postcss-8.5.31.tgz",
          "integrity": "%s",
          """.formatted(POSTCSS_INTEGRITY))
      .replace("\"nanoid\": \"^3.3.11\"", "\"nanoid\": \"^3.3.20\"");

  @Test
  void regeneratedUpdateIsVersionsOnly() {
    Verdict verdict = NpmLockPins.check(BASE, HEAD);

    assertThat(verdict.versionsOnly()).as(verdict.reason()).isTrue();
    assertThat(verdict.changes())
        .extracting(NpmLockPins.Change::name, NpmLockPins.Change::from, NpmLockPins.Change::to)
        .containsExactly(
            tuple("nanoid", "3.3.11", "3.3.20"),
            tuple("postcss", "8.5.6", "8.5.31"));
  }

  @Test
  void requestedPackageMustReachItsFixedReleaseInEveryCopy() {
    assertThat(NpmLockPins.below(HEAD, "postcss", "8.5.23")).isEmpty();
    assertThat(NpmLockPins.below(HEAD, "postcss", "8.5.31")).isEmpty();
    assertThat(NpmLockPins.below(HEAD, "postcss", "8.5.32")).containsExactly("8.5.31");
    assertThat(NpmLockPins.below(BASE, "nanoid", "3.3.18")).containsExactly("3.3.11");

    String nested = "\"node_modules/vite/node_modules/postcss\": { \"version\": \"8.4.0\" },";
    String postcss = "\"node_modules/postcss\": {";
    String twoCopies = HEAD.replace(postcss, nested + postcss);
    assertThat(NpmLockPins.below(twoCopies, "postcss", "8.5.23")).containsExactly("8.4.0");
  }

  @Test
  void missingPackageOrUnreadableLockfileNeverReadsAsReached() {
    assertThat(NpmLockPins.below(HEAD, "left-pad", "1.0.0")).containsExactly("(not installed)");
    assertThat(NpmLockPins.below("{not json", "postcss", "8.5.23"))
        .containsExactly("(unreadable)");
    assertThat(NpmLockPins.below(null, "postcss", "8.5.23")).containsExactly("(unreadable)");
    assertThat(NpmLockPins.below(HEAD, "postcss", "latest")).containsExactly("(unreadable)");
  }

  @Test
  void tooManyMovedPackagesIsRefused() {
    StringBuilder base = new StringBuilder("{\"packages\": {\"\": {}");
    StringBuilder head = new StringBuilder("{\"packages\": {\"\": {}");
    for (int i = 0; i <= NpmLockPins.MAX_CHANGES; i++) {
      base.append(",\"node_modules/p%d\": {\"version\": \"1.0.0\"}".formatted(i));
      String tarball = "https://registry.npmjs.org/p%d/-/p%d-1.0.1.tgz".formatted(i, i);
      String entry = ",\"node_modules/p%d\": {\"version\": \"1.0.1\", \"resolved\": \"%s\","
          .formatted(i, tarball);
      head.append(entry).append(" \"integrity\": \"%s\"}".formatted(NANOID_INTEGRITY));
    }
    base.append("}}");
    head.append("}}");

    assertThat(NpmLockPins.check(base.toString(), head.toString()).reason())
        .contains("moves 26 packages");
  }

  @Test
  void scopedNestedPackageResolvesToItsScopedTarball() {
    String head = BASE.replace("1.2.3", "1.2.4");

    Verdict verdict = NpmLockPins.check(BASE, head);

    assertThat(verdict.versionsOnly()).as(verdict.reason()).isTrue();
    assertThat(verdict.changes().get(0).name()).isEqualTo("@scope/thing");
  }

  @Test
  void addedOrRemovedPackageIsRefused() {
    String added = HEAD.replace("""
          "node_modules/postcss": {""", """
          "node_modules/evil": { "version": "1.0.0" },
          "node_modules/postcss": {""");
    assertThat(NpmLockPins.check(BASE, added).reason()).contains("adds `node_modules/evil`");

    String removed = BASE.replace("\"node_modules/nanoid\"", "\"node_modules/nanoid-gone\"");
    assertThat(NpmLockPins.check(BASE, removed).versionsOnly()).isFalse();
  }

  @Test
  void tarballFromAnywhereButTheRegistryIsRefused() {
    for (String url : new String[] {
        "https://evil.example/nanoid/-/nanoid-3.3.20.tgz",
        "https://registry.npmjs.org.evil.example/nanoid/-/nanoid-3.3.20.tgz",
        "https://registry.npmjs.org/nanoid/-/nanoid-3.3.19.tgz",
        "https://registry.npmjs.org/evil/-/evil-3.3.20.tgz",
        "git+https://github.com/evil/nanoid.git"}) {
      String head = HEAD.replace(
          "https://registry.npmjs.org/nanoid/-/nanoid-3.3.20.tgz", url);
      Verdict verdict = NpmLockPins.check(BASE, head);
      assertThat(verdict.versionsOnly()).as(url).isFalse();
      assertThat(verdict.reason()).as(url).contains("npm registry's tarball");
    }
  }

  @Test
  void missingOrWeakIntegrityIsRefused() {
    String sha1 = HEAD.replace(NANOID_INTEGRITY, "sha1-abcdefghijklmnopqrstuvwxyz0=");
    assertThat(NpmLockPins.check(BASE, sha1).reason()).contains("SHA-512");
  }

  @Test
  void versionMustRiseWithinTheMajorToRelease() {
    for (String version : new String[] {"3.3.10", "3.3.11", "4.0.0", "3.4.0-beta.1", "latest"}) {
      String head = HEAD
          .replace("\"version\": \"3.3.20\"", "\"version\": \"" + version + "\"")
          .replace("nanoid-3.3.20.tgz", "nanoid-" + version + ".tgz");
      assertThat(NpmLockPins.check(BASE, head).versionsOnly()).as(version).isFalse();
    }
  }

  @Test
  void preReleaseMayMoveToItsRelease() {
    String base = BASE.replace("\"version\": \"3.3.11\"", "\"version\": \"3.3.20-rc.1\"");

    assertThat(NpmLockPins.check(base, HEAD).versionsOnly()).isTrue();
  }

  @Test
  void anyOtherFieldChangeIsRefused() {
    String bin = HEAD.replace("bin/nanoid.cjs", "bin/evil.cjs");
    assertThat(NpmLockPins.check(BASE, bin).reason())
        .contains("changes `bin` of `node_modules/nanoid`");

    String script = HEAD.replace(
        "\"dependencies\": { \"nanoid\"",
        "\"hasInstallScript\": true, \"dependencies\": { \"nanoid\"");
    assertThat(NpmLockPins.check(BASE, script).reason()).contains("`hasInstallScript`");

    String dev = HEAD.replace("\"dev\": true", "\"dev\": false");
    assertThat(NpmLockPins.check(BASE, dev).versionsOnly()).isFalse();
  }

  @Test
  void rootAndTopLevelFieldsMustNotChange() {
    String root = HEAD.replace("\"vite\": \"^6.0.0\"", "\"vite\": \"^7.0.0\"");
    assertThat(NpmLockPins.check(BASE, root).reason()).contains("(root)");

    String lockfileVersion = HEAD.replace("\"lockfileVersion\": 3", "\"lockfileVersion\": 2");
    assertThat(NpmLockPins.check(BASE, lockfileVersion).reason()).contains("`lockfileVersion`");
  }

  @Test
  void unreadableOrUnchangedLockfilesAreRefused() {
    assertThat(NpmLockPins.check(null, HEAD).versionsOnly()).isFalse();
    assertThat(NpmLockPins.check(BASE, null).versionsOnly()).isFalse();
    assertThat(NpmLockPins.check(BASE, "{not json").reason()).contains("not valid JSON");
    assertThat(NpmLockPins.check(BASE, "[]").versionsOnly()).isFalse();
    assertThat(NpmLockPins.check(BASE, BASE).reason()).contains("no package version changed");
    assertThat(NpmLockPins.check(BASE, "x".repeat(NpmLockPins.MAX_CHARS + 1)).reason())
        .contains("too large");
  }

  @Test
  void entryKeysNameTheirPackage() {
    assertThat(NpmLockPins.nameOf("node_modules/a/node_modules/@s/b")).isEqualTo("@s/b");
    assertThat(NpmLockPins.nameOf("node_modules/postcss")).isEqualTo("postcss");
    assertThat(NpmLockPins.nameOf("")).isNull();
    assertThat(NpmLockPins.nameOf("packages/app")).isNull();
    assertThat(NpmLockPins.nameOf("xnode_modules/a")).isNull();
    assertThat(NpmLockPins.nameOf("node_modules/../../etc")).isNull();
  }

  /** Long values in the places the patterns read; possessive quantifiers keep each check linear. */
  @Test
  void adversarialValuesAreRefusedQuickly() {
    String longVersion = "3." + "3".repeat(100_000) + ".20";
    String longName = "a".repeat(100_000);
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      assertThat(NpmLockPins.check(BASE, HEAD.replace("3.3.20", longVersion)).versionsOnly())
          .isFalse();
      assertThat(NpmLockPins.check(BASE, HEAD.replace(NANOID_INTEGRITY, "sha512-" + longName))
          .versionsOnly()).isFalse();
      assertThat(NpmLockPins.nameOf("node_modules/" + longName)).isNull();
    });
  }
}
