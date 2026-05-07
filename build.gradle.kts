plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.mohamedali.ledger"
version = "0.0.1-SNAPSHOT"
description = "Account Ledger Service"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val testcontainersVersion = "1.21.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-otlp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
