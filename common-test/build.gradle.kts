description = "Integration test harness: containers pinned to the images the compose stack uses"

dependencies {
    api(platform(libs.testcontainers.bom))
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.springframework:spring-test")
    api("org.springframework:spring-context")
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.assertj:assertj-core")
    // What the shared schema contract asserts against: the service's own Flyway and the
    // health endpoint its container healthcheck calls.
    api("org.flywaydb:flyway-core")
    api("org.springframework.boot:spring-boot-health")
    api("org.springframework.boot:spring-boot-actuator")
    api("org.postgresql:postgresql")

    testImplementation("org.apache.kafka:kafka-clients")
}
