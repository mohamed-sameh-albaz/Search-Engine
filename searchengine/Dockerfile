# Stage 1: Build the application
FROM maven:3.9.6 AS build
WORKDIR /app
# Copy only the POM file first to leverage Docker cache for dependencies
COPY pom.xml .
# Download all required dependencies
RUN mvn dependency:go-offline -B
# Copy the source code
COPY src ./src
# Package the application
RUN mvn clean package -DskipTests

# Stage 2: Create the runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
# Copy the jar file from the build stage
COPY --from=build /app/target/*.jar app.jar
# Add wait-for-it script to ensure database is ready before starting
ADD https://raw.githubusercontent.com/vishnubob/wait-for-it/master/wait-for-it.sh /wait-for-it.sh
RUN chmod +x /wait-for-it.sh
# Expose the application port
EXPOSE 8080
# Set healthcheck to monitor the application
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
# Run the application with proper JVM settings
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
