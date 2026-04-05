FROM gradle:8-jdk21-alpine AS builder

WORKDIR /app

COPY build.gradle gradlew gradlew.bat ./
COPY gradle/ gradle/

RUN gradle dependencies --no-daemon

COPY src/ src/

RUN gradle build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S botgroup && adduser -S botuser -G botgroup

COPY --from=builder /app/build/libs/*.jar app.jar

RUN mkdir -p /app/config /app/data && chown -R botuser:botgroup /app

USER botuser

ENV DB_FILE=/app/data/bot.db

ENTRYPOINT ["java", "-jar", "app.jar"]
