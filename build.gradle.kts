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

/** The Tomcat this platform runs until Spring Boot manages a fixed one. See subprojects. */
val tomcatAheadOfBoot = "11.0.25"

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

        // Ahead of the Boot BOM, on purpose and for as short a time as possible. Spring Boot
        // 4.0.8 manages Tomcat 11.0.24, which has three CRITICAL findings fixed in 11.0.25, and
        // no Boot release manages 11.0.25 yet. The image scan (MIZ-82) found them; this is the
        // fix rather than an exception, because a fix exists. Remove it in the same commit that
        // moves springBoot to a release managing 11.0.25 or later; left behind it is harmless
        // until the day it pins Tomcat below what Boot would have chosen, so it is not optional.
        constraints {
            listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket").forEach {
                add("implementation", "org.apache.tomcat.embed:$it") {
                    version { require(tomcatAheadOfBoot) }
                    because("CVE-2026-65182, CVE-2026-65905, CVE-2026-68525 fixed in 11.0.25")
                }
            }
        }
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

    val testSources = extensions.getByType<SourceSetContainer>()["test"]

    // Rewrites the committed OpenAPI specs from the ones the services generate. The same
    // tests that compare the two do the writing, so the export cannot drift from the check.
    tasks.register<Test>("exportOpenApi") {
        group = "documentation"
        description = "Rewrites docs/api from the spec each service generates"

        testClassesDirs = testSources.output.classesDirs
        classpath = testSources.runtimeClasspath

        systemProperty("mizan.openapi.write", "true")
        filter {
            includeTestsMatching("*OpenApiSpecTest")
            isFailOnNoMatchingTests = false
        }
        outputs.upToDateWhen { false }
    }
}
