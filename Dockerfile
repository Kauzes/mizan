# Shared by every service. MODULE is used only from the layers stage on, so the build stage
# is identical for all of them and Docker builds it once.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
# Everything else, so adding a module cannot silently break the image build. What stays
# out of the context is in .dockerignore.
COPY . .
RUN ./gradlew --no-daemon bootJar
# The container healthcheck, compiled here because the runtime image has no compiler and no
# curl. One class, a few kilobytes. See image/Healthcheck.java.
RUN mkdir -p /healthcheck && javac --release 21 -d /healthcheck image/Healthcheck.java

# The jar, split into layers by how often each one changes: dependencies almost never,
# the application on every commit. A code change then rebuilds and pushes a layer of
# kilobytes rather than the whole dependency tree.
FROM builder AS layers
ARG MODULE
WORKDIR /layers
RUN cp /src/services/${MODULE}/build/libs/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted \
    && rm app.jar

# Distroless: a Java runtime and nothing else. No shell, no package manager, no curl — each
# of those is something a compromised process could use and none is something a service
# needs. The nonroot variant runs as uid 65532 and the platform's ImageTest holds it there.
FROM gcr.io/distroless/java21-debian12:nonroot
WORKDIR /app
COPY --from=builder /healthcheck /healthcheck
# Least to most frequently changed, which is what makes the layers worth having.
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER nonroot
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
