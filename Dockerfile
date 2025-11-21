# Adapted from 
# https://dev.to/minuth/dockerized-spring-boot-with-multi-environment-configs-5h4p

# Stage 1: Build the application
FROM maven:3.9-eclipse-temurin-25-alpine AS build

# Build arguments for GitHub authentication
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

# Set the working directory in the container
WORKDIR /app

# Create settings.xml with GitHub authentication
RUN mkdir -p /root/.m2 && \
    echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" \
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 \
          https://maven.apache.org/xsd/settings-1.0.0.xsd"> \
      <servers> \
        <server> \
          <id>github-doda2025-team8</id> \
          <username>${GITHUB_ACTOR}</username> \
          <password>${GITHUB_TOKEN}</password> \
        </server> \
      </servers> \
    </settings>' > /root/.m2/settings.xml

# Copy the pom.xml and download dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# Build the application
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:25-jre-alpine

# Set the working directory in the container
WORKDIR /app

# Copy the jar file from the build stage
COPY --from=build /app/target/*.jar app.jar

# Install curl for health checks
RUN apk add --no-cache curl

# Command to run the application
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.location=optional:classpath:/,optional:file:config/"]
