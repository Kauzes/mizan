description = "Shared contracts: money, errors, correlation context, event schemas"

dependencies {
    implementation("org.slf4j:slf4j-api")
    compileOnly("org.apache.kafka:kafka-clients")
    testImplementation("org.apache.kafka:kafka-clients")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
