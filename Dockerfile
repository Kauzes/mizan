# Shared by every service. MODULE is used only in the runtime stage, so the build stage
# is identical for all of them and Docker builds it once.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /src
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY common common
COPY services services
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine
ARG MODULE
RUN apk add --no-cache curl \
    && addgroup -S mizan \
    && adduser -S mizan -G mizan
WORKDIR /app
COPY --from=builder /src/services/${MODULE}/build/libs/*.jar app.jar
USER mizan
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
