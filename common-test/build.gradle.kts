description = "Integration test harness: containers pinned to the images the compose stack uses"

dependencies {
    api(platform(libs.testcontainers.bom))
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")
    api(project(":common"))
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
    // What the shared API contract asserts against: the spec the running service serves.
    api("org.springframework.boot:spring-boot-webmvc-test")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("jakarta.servlet:jakarta.servlet-api")

    testImplementation("org.apache.kafka:kafka-clients")
}

// These tests read files that are on no classpath: the Compose file, .env, the scrape
// configuration, the Grafana provisioning and dashboards, every service's application.yml,
// and every service's production sources. Gradle cannot infer that, so editing a dashboard
// leaves the test that checks the dashboards UP-TO-DATE and the check quietly does not run —
// which was found the first time a metric name in a dashboard was deliberately broken and the
// build stayed green.
tasks.named<Test>("test") {
    inputs.files(rootProject.file("docker-compose.yml"), rootProject.file(".env"))
        .withPropertyName("platformFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.dir(rootProject.file("deploy/local"))
        .withPropertyName("deployFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.files(rootProject.fileTree("services") {
        include("*/src/main/resources/application.yml")
        include("*/src/main/java/**/*.java")
        include("*/src/test/java/**/*.java")
    })
        .withPropertyName("serviceFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
