# ---- 构建阶段：容器内完成 Gradle 编译，保证 compose 一键启动无需本机 JDK ----
FROM gradle:8.10.2-jdk17 AS builder
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY src src
RUN gradle bootJar --no-daemon -x test

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /workspace/build/libs/studyroom-booking-1.0.0.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=15s --timeout=3s --retries=10 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
