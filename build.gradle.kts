plugins {
    alias(libs.plugins.spring.boot) apply false
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
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
