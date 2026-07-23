# 1. Builder Stage
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew build -x test --no-daemon

# 2. Runner Stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --chown=spring:spring agent/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --chown=spring:spring --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-Xmx256m", "-jar", "app.jar"]