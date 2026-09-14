plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The scrape endpoint. The actuator alone measures plenty and has nowhere to
    // put it; this is what makes the numbers somebody else can collect.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // One payment, one trace. Boot 4 keeps tracing in modules of its own, as it does for
    // Flyway and the HTTP clients, and the starter is what brings the bridge from Micrometer's
    // observations to OpenTelemetry spans together with the exporter that gets them out of the
    // process. A runtime concern: no code in this service imports any of it.
    runtimeOnly("org.springframework.boot:spring-boot-starter-opentelemetry")
    // Boot 4 keeps the reactive client's autoconfiguration in its own module. The gateway
    // needs it to fetch identity's public keys.
    implementation("org.springframework.boot:spring-boot-webclient")
    implementation(libs.springdoc.webflux.ui)
    // Verifying a signature, and nothing else. The gateway holds no key that could mint a
    // token: it fetches the public half identity publishes.
    implementation(libs.nimbus.jose.jwt)
    implementation(project(":common-web"))
}
