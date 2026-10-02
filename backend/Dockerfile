# syntax=docker/dockerfile:1

# ---- Build stage: compile and package the Spring Boot jar -------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy only what is needed to resolve dependencies first, so this layer is cached
# until the build descriptor or wrapper actually changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# The Maven wrapper must be executable and use LF line endings (it may have been
# committed from Windows). Strip any trailing CR, then make it executable.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

# Warm the local Maven repo. Cached as long as pom.xml / the wrapper are unchanged.
RUN ./mvnw -q -B dependency:go-offline

# Now bring in the sources and build the jar (tests run in CI, not in the image).
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package

# ---- Runtime stage: minimal JRE, non-root ----------------------------------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# Run as an unprivileged user rather than root.
RUN groupadd --system app && useradd --system --gid app --home /app app

# Copy the built jar (there is exactly one bootable jar in target/).
COPY --from=build /app/target/*.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]