plugins {
    alias(libs.plugins.spring.boot) apply false
}

// Compose reads .env directly. The same values reach the tests, so the harness cannot
// start a different Postgres or Kafka than the one the platform runs on.
val platformImages: Map<String, String> = file("$rootDir/.env").readLines()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

/**
 * Every service that owns a database starts containers in its own test JVM. Several of them
 * reaching for the Docker daemon at the same moment is how a build fails on a machine where
 * Docker is perfectly healthy, so container backed test tasks take turns. Nothing serialises
 * a run with -PfastTests, which touches no containers at all.
 */
abstract class DockerAccess : BuildService<BuildServiceParameters.None>

val fastTests = providers.gradleProperty("fastTests").isPresent

val dockerAccess = gradle.sharedServices.registerIfAbsent("dockerAccess", DockerAccess::class) {
    maxParallelUsages.set(1)
}

val springBootBom = libs.spring.boot.bom
val springCloudBom = libs.spring.cloud.bom
val testcontainersBom = libs.testcontainers.bom

allprojects {
    group = "dev.kauzes.mizan"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        add("implementation", platform(springBootBom))
        add("implementation", platform(springCloudBom))
        add("testImplementation", platform(testcontainersBom))
        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            if (fastTests) {
                excludeTags("integration")
            }
        }
        if (!fastTests) {
            usesService(dockerAccess)
        }
        platformImages.forEach { (key, value) -> systemProperty("mizan.env.$key", value) }
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
