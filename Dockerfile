# syntax=docker/dockerfile:1

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency layer: re-downloaded only when pom.xml changes
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# ---------- runtime stage ----------
# Slim JRE image: no Chrome here — the Selenium fallback talks to the
# selenium/standalone-chrome container via zara.driver.remote-url
FROM eclipse-temurin:21-jre
WORKDIR /app

# curl is needed only for the compose healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root user; dumps/ is where PageDumper writes parsing-failure snapshots
RUN useradd --system --home /app zara \
    && mkdir -p /app/dumps \
    && chown -R zara /app
USER zara

COPY --from=build /build/target/zara-*.jar app.jar

EXPOSE 8100
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
