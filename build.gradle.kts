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
extra["awsSdkVersion"] = "2.28.29"
extra["opensearchClientVersion"] = "2.13.0"
extra["redissonVersion"] = "3.37.0"
extra["shedlockVersion"] = "5.16.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAPI 3 docs + Swagger UI — typed contract for the storefront SPA
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    implementation("software.amazon.awssdk:s3")

    implementation("org.opensearch.client:opensearch-rest-client:${property("opensearchClientVersion")}")
    implementation("org.opensearch.client:opensearch-java:${property("opensearchClientVersion")}")

    implementation("org.redisson:redisson:${property("redissonVersion")}")

    // ShedLock for distributed scheduler locking
    implementation("net.javacrumbs.shedlock:shedlock-spring:${property("shedlockVersion")}")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:${property("shedlockVersion")}")

    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
    testImplementation("org.opensearch:opensearch-testcontainers:2.1.3")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
        mavenBom("software.amazon.awssdk:bom:${property("awsSdkVersion")}")
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

// OLV-100: ./gradlew reindexProducts — `reindex` 프로필을 활성화한 부팅으로
// 전체 products 테이블을 OpenSearch에 bulk 색인하고 정상 종료한다.
tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("reindexProducts") {
    group = "application"
    description = "Rebuild the OpenSearch products index from Postgres (OLV-100)."
    mainClass.set("com.olive.commerce.CommerceBackendApplication")
    classpath = sourceSets["main"].runtimeClasspath
    args(
        "--spring.profiles.active=" + (System.getenv("SPRING_PROFILES_ACTIVE") ?: "local,reindex")
    )
}

// ---------------------------------------------------------------------------
// Storefront SPA build (frontend/ -> src/main/resources/static/app).
// Wired only into packaging/run tasks (bootJar, bootRun) so `test` and the
// `compile*` tasks stay Node-free. `vite build` writes into static/app, which
// is gitignored and picked up by processResources for a self-contained jar.
// ---------------------------------------------------------------------------
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val npm = if (isWindows) "npm.cmd" else "npm"
val frontendDir = layout.projectDirectory.dir("frontend")
val spaOutputDir = layout.projectDirectory.dir("src/main/resources/static/app")

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "frontend"
    description = "Install storefront SPA dependencies (npm ci)."
    workingDir = frontendDir.asFile
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("package-lock.json"))
    outputs.dir(frontendDir.dir("node_modules"))
    commandLine(npm, "ci")
}

val npmBuild = tasks.register<Exec>("npmBuild") {
    group = "frontend"
    description = "Build the storefront SPA into static/app (Vite)."
    dependsOn(npmInstall)
    workingDir = frontendDir.asFile
    inputs.dir(frontendDir.dir("src"))
    inputs.dir(frontendDir.dir("public"))
    inputs.file(frontendDir.file("package.json"))
    inputs.file(frontendDir.file("vite.config.ts"))
    inputs.file(frontendDir.file("tsconfig.json"))
    inputs.file(frontendDir.file("index.html"))
    outputs.dir(spaOutputDir)
    commandLine(npm, "run", "build")
}

// SPA must be built before resources are copied into the jar / runtime classpath.
tasks.named("processResources") { mustRunAfter(npmBuild) }
tasks.named("bootJar") { dependsOn(npmBuild) }
tasks.named("bootRun") { dependsOn(npmBuild) }
