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
    // Boot 4 keeps RestClient's autoconfiguration in its own module. The ledger is reached
    // over HTTP, because settlement writes entries and does not own the books.
    implementation("org.springframework.boot:spring-boot-restclient")
    implementation(libs.springdoc.webmvc.ui)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-flyway")
    // Batches are plain SQL over tables this service owns. There is no aggregate here that
    // needs an object graph: a batch is a row and its items are rows, and the interesting
    // logic is arithmetic rather than state.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Captures arrive as events, the same way risk and notification learn what happened.
    // Settlement never reads the payment database.
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-kafka")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":common-web"))

    testImplementation(project(":common-test"))
    // The services that produce what this one settles, so a test can drive real payments
    // through and settle the events they really publish rather than this test's idea of them.
    testImplementation(project(":services:payment-service"))
    testImplementation(project(":services:ledger-service"))
    testImplementation(project(":services:bank-simulator"))
}
