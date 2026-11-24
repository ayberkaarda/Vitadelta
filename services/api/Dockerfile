# syntax=docker/dockerfile:1

FROM eclipse-temurin:25.0.5.1_2-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet help
COPY src ./src
RUN ./gradlew --no-daemon bootJar \
    && java -Djarmode=tools -jar build/libs/vitadelta-api-0.0.1.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:25.0.5.1_2-jre-noble
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 vitadelta \
    && useradd --system --uid 10001 --gid vitadelta --no-create-home --shell /usr/sbin/nologin vitadelta
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
