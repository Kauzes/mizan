plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.springdoc.webmvc.ui)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-flyway")
    // The first service on this platform that listens rather than answers. Both are needed:
    // spring-kafka is the library and spring-boot-kafka is the autoconfiguration that turns
    // it into beans, which Boot 4 keeps apart.
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-kafka")
    // The inbox writes plain SQL to a table this service owns, exactly as the outbox does.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":common-web"))

    testImplementation(project(":common-test"))
    // Payments are produced by the payment service, and a test that made up its own messages
    // would be testing this service against this test's idea of the contract. The events here
    // are the ones that service actually publishes.
    testImplementation(project(":services:payment-service"))
    testImplementation(project(":services:ledger-service"))
    testImplementation(project(":services:bank-simulator"))
}
