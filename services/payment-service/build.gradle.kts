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
    // The first service to publish anything. The relay and the publisher are in common-web;
    // these are what put a KafkaTemplate in the context for them to find. Boot 4 keeps the
    // autoconfiguration in a module of its own, as it does for Flyway and the HTTP clients:
    // spring-kafka alone gives you the library and no beans, and the failure is a missing
    // bean rather than anything that mentions Kafka.
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-kafka")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":common-web"))

    testImplementation(project(":common-test"))
    // The acquirer, started in the test JVM. A stub here would encode this service's
    // assumptions about the wire and keep passing if the acquirer changed shape; the
    // simulator is test infrastructure by nature, so depending on it in tests is honest.
    testImplementation(project(":services:bank-simulator"))
    // And the ledger, for the same reason. A capture is only correct if the entry it writes
    // is one the ledger will actually accept, and that is a question about two services.
    testImplementation(project(":services:ledger-service"))
}
