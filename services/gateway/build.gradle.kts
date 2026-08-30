plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Boot 4 keeps the reactive client's autoconfiguration in its own module. The gateway
    // needs it to fetch identity's public keys.
    implementation("org.springframework.boot:spring-boot-webclient")
    implementation(libs.springdoc.webflux.ui)
    // Verifying a signature, and nothing else. The gateway holds no key that could mint a
    // token: it fetches the public half identity publishes.
    implementation(libs.nimbus.jose.jwt)
    implementation(project(":common-web"))
}
