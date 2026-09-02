plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.springdoc.webmvc.ui)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Boot 4 keeps the blocking client's autoconfiguration in its own module. This service
    // is the only one that calls out to somebody else's system.
    implementation("org.springframework.boot:spring-boot-restclient")
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":common-web"))

    testImplementation(project(":common-test"))
    // The acquirer, started in the test JVM. A stub here would encode this service's
    // assumptions about the wire and keep passing if the acquirer changed shape; the
    // simulator is test infrastructure by nature, so depending on it in tests is honest.
    testImplementation(project(":services:bank-simulator"))
}
