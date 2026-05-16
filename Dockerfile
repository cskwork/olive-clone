# Multi-stage Dockerfile for Commerce Backend (OLV-130)
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Gradle 빌드 캐시를 위한 설정 (Gradle Daemon 비활성화)
COPY gradlew .
COPY gradle gradle
COPY gradle.properties .
COPY build.gradle.kts .
COPY settings.gradle.kts .

# 종속성 다운로드 (레이어 캐시 활용)
RUN ./gradlew dependencies --no-daemon

# 소스 코드 복사
COPY src src
COPY src/main/resources/application*.yml src/main/resources/

# 빌드 (JAR 생성)
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 비루트 사용자 생성 (보안)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 빌드 결과물 복사
COPY --from=builder /build/build/libs/*.jar app.jar

# 헬스체크용 포트 노출
EXPOSE 8080

# JVM 옵션 (Alpine 리눅스 최적화)
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# 헬스체크
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
