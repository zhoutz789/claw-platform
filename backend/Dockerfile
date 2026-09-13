# Claw 后端镜像（多阶段构建）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml maven-settings.xml .
# CN 服务器构建走阿里云镜像（见 maven-settings.xml），避免直连 Maven Central 超时
RUN mvn -B -s maven-settings.xml dependency:go-offline
COPY src ./src
RUN mvn -B -s maven-settings.xml package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S claw && adduser -S claw -G claw
USER claw
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
