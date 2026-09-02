description = "Spring wiring shared by every service: problem details, correlation id propagation"

dependencies {
    api(project(":common"))
    api("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")

    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("jakarta.validation:jakarta.validation-api")
    // The idempotency store is plain SQL against a table each service owns. Optional here:
    // a service with no database gets the annotations and the startup check, and no store.
    compileOnly("org.springframework:spring-jdbc")
    // The outbox serialises an event's payload. Optional for the same reason: a service
    // with no database publishes nothing and needs neither.
    compileOnly("org.springframework:spring-tx")
    compileOnly("tools.jackson.core:jackson-databind")
    compileOnly(libs.springdoc.common)

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    // The library, not the starter: the starter brings DataSource autoconfiguration, and
    // these tests have no database to point it at.
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.springframework:spring-tx")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation(libs.springdoc.webmvc.ui)
}
