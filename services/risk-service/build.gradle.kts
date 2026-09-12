plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The scrape endpoint. The actuator alone measures plenty and has nowhere to
    // put it; this is what makes the numbers somebody else can collect.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.springdoc.webmvc.ui)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-flyway")
    // Thresholds and, from MIZ-57, baselines are plain SQL against tables this service owns.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Baselines are built from payment events, consumed the same way notification-service
    // consumes them. Risk never reads the payment database: that boundary is the whole reason
    // these are separate services.
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-kafka")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":common-web"))

    testImplementation(project(":common-test"))
    // The payment service, so a test can drive real payments through and let this service
    // learn from the events it really publishes rather than from this test's idea of them.
    testImplementation(project(":services:payment-service"))
    testImplementation(project(":services:ledger-service"))
    testImplementation(project(":services:bank-simulator"))
}
