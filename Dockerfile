# ==============================================================================
# 1. Build Stage: Compile & Assemble Executable JAR
# ==============================================================================
FROM gradle:9.3.0-jdk21-alpine AS builder

WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN GRADLE_USER_HOME=/gradle-cache gradle bootJar -x test --no-daemon

# ==============================================================================
# 2. Custom JRE Stage: Generate ultra-minimal stripped Java 21 runtime with jlink
# ==============================================================================
FROM eclipse-temurin:21-jdk-alpine AS jlink

RUN jlink \
    --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.zipfs \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /custom-jre

# ==============================================================================
# 3. Final Minimal Runtime Stage (Alpine + Custom JRE)
# ==============================================================================
FROM alpine:3.20

LABEL maintainer="ShoonyaTradingBot"

ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"

# Install curl for healthcheck and tzdata for IST (Asia/Kolkata)
# hadolint ignore=DL3018
RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Kolkata /etc/localtime && \
    echo "Asia/Kolkata" > /etc/timezone

# Create non-root system user (UID 10001) and allocate directories
RUN addgroup -g 10001 -S appgroup && \
    adduser -u 10001 -S appuser -G appgroup && \
    mkdir -p /app/data /app/logs && \
    chown -R 10001:10001 /app

# Copy custom minimal JRE
COPY --from=jlink --chown=10001:10001 /custom-jre /opt/java

WORKDIR /app

# Copy executable Spring Boot application jar
COPY --from=builder --chown=10001:10001 /app/build/libs/*.jar /app/app.jar

USER 10001:10001

# Expose HTTP / Actuator port
EXPOSE 8080

# Environment and JVM settings for low-memory VPS
ENV JAVA_OPTS="-XX:+UseSerialGC -Xms128m -Xmx384m -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Kolkata -Djava.security.egd=file:/dev/./urandom"
ENV SERVER_PORT=8080
ENV LOGGING_FILE_NAME=/app/logs/shoonya-trading-bot.log

VOLUME ["/app/data", "/app/logs"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD ["curl", "-f", "http://localhost:8080/actuator/health"]

STOPSIGNAL SIGTERM

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
