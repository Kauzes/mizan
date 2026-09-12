plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The scrape endpoint. The actuator alone measures plenty and has nowhere to
    // put it; this is what makes the numbers somebody else can collect.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.springdoc.webmvc.ui)
    implementation(project(":common-web"))

    testImplementation(project(":common-test"))
}
