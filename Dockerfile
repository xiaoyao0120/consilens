#
# Dockerfile for consilens-server
#
# Runs the Spring Boot based consilens-server using an already-pulled
# JDK 11 image to avoid Docker Hub mirror issues.
#

FROM mcr.microsoft.com/openjdk/jdk:11-ubuntu

# Metadata
LABEL maintainer="consilens"
LABEL description="Consilens Server - Cross-datasource data consistency checker"

# Build argument for the JAR path
ARG JAR_PATH=consilens-server/target/consilens-server-0.1-SNAPSHOT.jar

# Ensure bash is available (useful for debugging)
RUN apt-get update && apt-get install -y --no-install-recommends wget bash && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /opt/consilens

# Copy the fat JAR
COPY ${JAR_PATH} consilens-server-0.1-SNAPSHOT.jar

# Create artifact storage directory (matches local profile default)
RUN mkdir -p /opt/consilens/.consilens-server/artifacts

# Expose the server port
EXPOSE 18080

# Run the server with 'local' profile (embedded H2, no API key)
ENTRYPOINT ["java", "-jar", "consilens-server-0.1-SNAPSHOT.jar", "--spring.profiles.active=local"]
