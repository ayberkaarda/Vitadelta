# syntax=docker/dockerfile:1

# The scoring client is generated at build time from contracts/scoring-api.openapi.yaml, which lives
# outside this build context. It comes from the named build context "contracts" (the repository's
# contracts directory): infra/docker-compose.yml declares it as an additional context; a manual
# build passes it explicitly, from the repository root:
#   docker build --build-context contracts=contracts services/api
FROM eclipse-temurin:25.0.5.1_2-jdk-noble AS build
WORKDIR /workspace/services/api
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet help
COPY --from=contracts scoring-api.openapi.yaml /workspace/contracts/scoring-api.openapi.yaml
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
