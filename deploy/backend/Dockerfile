# syntax=docker/dockerfile:1
# Spring Boot backend. Build context: the yousong-backend repository root (see Dockerfile.dockerignore: no .env,
# no build output and no analytics/deploy folders enter the image).
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon --console=plain dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon --console=plain bootJar -x test \
    && cp build/libs/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:25-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10002 yousong
COPY --from=build /app.jar /app/app.jar
WORKDIR /app
USER yousong
ENV TZ=UTC
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
