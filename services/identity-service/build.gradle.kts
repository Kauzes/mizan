plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The scrape endpoint. The actuator alone measures plenty and has nowhere to
    // put it; this is what makes the numbers somebody else can collect.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // One payment, one trace. Boot 4 keeps tracing in modules of its own, as it does for
    // Flyway and the HTTP clients, and the starter is what brings the bridge from Micrometer's
    // observations to OpenTelemetry spans together with the exporter that gets them out of the
    // process. A runtime concern: no code in this service imports any of it.
    runtimeOnly("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation(libs.springdoc.webmvc.ui)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The password hashing, without the filter chain. Authentication arrives at the
    // gateway in MIZ-30; this service only has to store a password it can verify later.
    implementation("org.springframework.security:spring-security-crypto")
    // Signing and verifying, without an OAuth2 server. Nothing here speaks OAuth2; the
    // platform issues its own tokens and the gateway verifies them against the published
    // key, which is a JOSE library's job rather than a framework's.
    implementation(libs.nimbus.jose.jwt)
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":common-web"))

    testImplementation(project(":common-test"))
}
