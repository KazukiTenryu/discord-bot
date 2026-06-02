FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /app

COPY build.gradle gradlew ./
COPY gradle/ gradle/
COPY src/ src/

# shadowJar runs migrateDb -> generateJooq -> compileJava, so the jOOQ sources are
# generated here from the migrations; they are not committed to the repo.
RUN ./gradlew shadowJar

FROM eclipse-temurin:25-jdk-alpine

WORKDIR /app

RUN addgroup -S botgroup && adduser -S botuser -G botgroup

COPY --from=builder /app/build/libs/app.jar app.jar

RUN mkdir -p /app/config /app/data && chown -R botuser:botgroup /app

USER botuser

ENV DB_FILE=/app/data/bot.db

# Playlist web player (bot.web.WebServer); override the port via the "webPort" config key.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
