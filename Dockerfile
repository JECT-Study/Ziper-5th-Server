FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY config/formatter ./config/formatter
COPY src ./src
RUN chmod +x gradlew && SENTRY_UPLOAD=false ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre-jammy AS runtime-base
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 ziper && useradd --uid 10001 --gid ziper --no-create-home ziper
ENV TZ=UTC
WORKDIR /app
USER 10001:10001
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=12 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:${PORT:-8080}/livez || exit 1
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]

# Release consumes the already tested JAR without invoking Gradle.
FROM runtime-base AS release
COPY --chown=ziper:ziper build/libs/ziper-server.jar /app/app.jar

# Local source builds remain available explicitly.
FROM runtime-base AS source
COPY --from=build --chown=ziper:ziper /workspace/build/libs/ziper-server.jar /app/app.jar
