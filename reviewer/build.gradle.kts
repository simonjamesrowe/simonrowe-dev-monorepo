plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    checkstyle
}

group = "com.simonrowe"
version = "0.1.0-SNAPSHOT"

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = rootProject.file("config/checkstyle/google_checks.xml")
    maxWarnings = 0
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.temporal.spring.boot.starter)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.temporal.testing)
}
