FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY backend/gradlew ./gradlew
COPY backend/gradle ./gradle
COPY backend/build.gradle backend/settings.gradle ./
RUN chmod +x ./gradlew

COPY backend/src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre

WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/build/libs/meeting-automation-backend-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 10000 9010

CMD ["sh", "-c", "exec java -jar /app/app.jar --server.address=0.0.0.0 --server.port=${PORT:-10000}"]
