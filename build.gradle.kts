buildscript {
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:10.20.1")
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.flywaydb.flyway") version "10.20.1"
}

group = "com.olive"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

extra["testcontainersVersion"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Desktop on macOS exposes the user-facing daemon at ~/.docker/run/docker.sock.
    // Ryuk reaper containers bind-mount whatever DOCKER_HOST points at, so the path must be
    // bind-mountable — docker.raw.sock is private to Docker Desktop and rejects bind mounts.
    // Resolve DOCKER_HOST in this priority:
    //   1) explicit env var (CI usually sets this)
    //   2) ~/.docker/run/docker.sock (Docker Desktop user socket — Ryuk-mountable)
    //   3) docker.raw.sock as last-resort fallback (older Docker Desktop without user socket)
    val home = System.getProperty("user.home")
    val dockerHost = System.getenv("DOCKER_HOST")
        ?: listOf(
            File(home, ".docker/run/docker.sock"),
            File(home, "Library/Containers/com.docker.docker/Data/docker.raw.sock")
        ).firstOrNull { it.exists() }?.let { "unix://${it.absolutePath}" }
    if (dockerHost != null) {
        environment("DOCKER_HOST", dockerHost)
        // Override any stale ~/.testcontainers.properties pointing at a stub socket.
        systemProperty("docker.host", dockerHost)
    }
}

flyway {
    url = System.getenv("FLYWAY_URL") ?: "jdbc:postgresql://localhost:5432/commerce"
    user = System.getenv("FLYWAY_USER") ?: "commerce"
    password = System.getenv("FLYWAY_PASSWORD") ?: "commerce"
    locations = arrayOf("classpath:db/migration")
}
