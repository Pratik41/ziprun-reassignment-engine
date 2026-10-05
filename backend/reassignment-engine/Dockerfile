# ---- Build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# Dependencies first, so code changes don't re-download them
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

# ---- Run ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/reassignment-engine-1.0-SNAPSHOT.jar app.jar
# H2 database file lives here (mounted as a volume by docker-compose)
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
