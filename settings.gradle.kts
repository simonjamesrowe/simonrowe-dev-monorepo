// The foojay resolver lets Gradle provision the Java toolchain itself. Without it,
// a `languageVersion` the local machine has no JDK for fails the build outright —
// which is exactly what moving the toolchain from 21 to 25 would otherwise have
// done on every machine that happens to have 21 on its PATH and nothing newer.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "simonrowe-dev-monorepo"

include("backend")
include("software-factory")
