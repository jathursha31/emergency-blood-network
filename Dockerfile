# ---------- Stage 1: build the jar with the Maven wrapper ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only the build files first, so dependencies are cached
# and re-downloaded only when pom.xml changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && cp target/blood-donor-network-*.jar app.jar

# ---------- Stage 2: small runtime image (JRE only, no build tools) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a non-root user
RUN useradd --system --uid 1001 spring
COPY --from=build /workspace/app.jar app.jar
USER spring

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
