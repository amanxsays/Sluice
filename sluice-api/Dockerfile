# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY sluice-core/pom.xml sluice-core/pom.xml
COPY sluice-api/pom.xml sluice-api/pom.xml
COPY mock-upstream/pom.xml mock-upstream/pom.xml
COPY sluice-core/src sluice-core/src
COPY sluice-api/src sluice-api/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl sluice-api -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /build/sluice-api/target/sluice-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
