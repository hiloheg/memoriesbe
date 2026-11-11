# Build stage: compile the jar with Maven (tests are run in CI/locally, not on the server).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Run stage: just the JRE and the jar, as a non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER app

# Port the API listens on (SERVER_PORT, default 8081).
EXPOSE 8081

# Cap the heap so the JVM can't take the whole server; adjust with JAVA_OPTS if needed.
ENV JAVA_OPTS="-Xmx768m -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
