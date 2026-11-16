# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN useradd --system --uid 10001 --no-create-home waypoint
WORKDIR /app
COPY --from=build /src/target/waypoint.jar /app/waypoint.jar
USER 10001
# 8080 public, 8081 admin (operator network only), 8082 management (JWKS, health)
EXPOSE 8080 8081 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/waypoint.jar"]
