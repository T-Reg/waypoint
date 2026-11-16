# syntax=docker/dockerfile:1
# Build from the repository root: docker build -f visualizer/Dockerfile .
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw ./
COPY visualizer/pom.xml visualizer/pom.xml
RUN ./mvnw -B -q -f visualizer/pom.xml dependency:go-offline
COPY visualizer/src visualizer/src
RUN ./mvnw -B -q -f visualizer/pom.xml package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN useradd --system --uid 10001 --no-create-home visualizer
WORKDIR /app
COPY --from=build /src/visualizer/target/waypoint-visualizer.jar /app/visualizer.jar
USER 10001
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/visualizer.jar"]
