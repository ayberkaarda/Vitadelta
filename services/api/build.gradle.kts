import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.2.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.12.0"
    id("org.openapi.generator") version "7.27.0"
}

group = "com.vitadelta"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Client of the scoring service, generated at build time from the authoritative contract
// (project specification section 9.2). The output lives under the build directory and is never
// committed; it is compiled in its own source set so that the strict compiler flags, the
// formatter and the coverage gate apply only to hand-written code.
val scoringContract = layout.projectDirectory.file("../../contracts/scoring-api.openapi.yaml")
val scoringClientDirectory = layout.buildDirectory.dir("generated/scoring-client")

val generateScoringClient by tasks.registering(GenerateTask::class) {
    description = "Generates the scoring service client from contracts/scoring-api.openapi.yaml."
    group = "build"
    generatorName = "java"
    library = "restclient"
    inputSpec = scoringContract
    outputDir = scoringClientDirectory
    invokerPackage = "com.vitadelta.api.recommendation.scoring"
    apiPackage = "com.vitadelta.api.recommendation.scoring.api"
    modelPackage = "com.vitadelta.api.recommendation.scoring.model"
    configOptions =
        mapOf(
            "useSpringBoot4" to "true",
            "useJackson3" to "true",
            "useJakartaEe" to "true",
            "openApiNullable" to "false",
            "dateLibrary" to "java8",
            "hideGenerationTimestamp" to "true",
            "sourceFolder" to "src/main/java",
        )
    generateApiTests = false
    generateModelTests = false
    generateApiDocumentation = false
    generateModelDocumentation = false
    cleanupOutput = true
    inputs.file(scoringContract).withPropertyName("scoringContract")
}

val scoringClient: SourceSet by sourceSets.creating {
    java.srcDir(scoringClientDirectory.map { it.dir("src/main/java") })
}

tasks.named(scoringClient.compileJavaTaskName) {
    dependsOn(generateScoringClient)
}

dependencies {
    // Libraries the generated client is compiled against (Spring RestClient, Jackson 3).
    scoringClient.implementationConfigurationName("org.springframework.boot:spring-boot-starter-webmvc")
    scoringClient.implementationConfigurationName("org.springframework.boot:spring-boot-starter-validation")
    implementation(scoringClient.output)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.2.1")
    implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
    testImplementation("org.wiremock:wiremock-standalone:4.0.0-beta.44")
    testImplementation("org.testcontainers:testcontainers")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.23.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.networknt:json-schema-validator:3.0.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (name == scoringClient.compileJavaTaskName) {
        options.compilerArgs.add("-parameters")
    } else {
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
    }
}

// Root of the repository's data directory, read by the seed and fixture integrity tests and, as
// vitadelta.seed.data-dir, by every application context (reference/weak_passwords.txt is loaded
// at startup).
val dataDirectory = layout.projectDirectory.dir("../../data")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("vitadelta.test.data-dir", dataDirectory.asFile.absolutePath)
    systemProperty("vitadelta.seed.data-dir", dataDirectory.asFile.absolutePath)
    inputs.dir(dataDirectory).withPropertyName("dataDirectory").optional()
    finalizedBy(tasks.named("jacocoTestReport"))
}

jacoco {
    toolVersion = "0.8.16"
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

tasks.named<Jar>("jar") {
    enabled = false
}

spotless {
    java {
        // Hand-written sources only; the generated scoring client is not formatted.
        target("src/**/*.java")
        googleJavaFormat("2.0.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
