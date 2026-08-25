description = "Integration test harness: containers pinned to the images the compose stack uses"

dependencies {
    api(platform(libs.testcontainers.bom))
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api("org.springframework:spring-test")
    api("org.junit.jupiter:junit-jupiter-api")

    testImplementation("org.postgresql:postgresql")
    testImplementation("org.apache.kafka:kafka-clients")
}
