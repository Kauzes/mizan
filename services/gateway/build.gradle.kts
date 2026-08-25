plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(project(":common-web"))
}
