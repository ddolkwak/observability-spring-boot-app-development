FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew
RUN ./gradlew clean build -x test

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=builder /app/build/libs/*.jar app.jar

COPY agent/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-Xmx256m", "-jar", "app.jar"]